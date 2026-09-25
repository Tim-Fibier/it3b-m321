package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.message.ChatMessage;
import ch.benedict.m321.batchwriter.message.ChatMessageParser;
import ch.benedict.m321.batchwriter.message.InvalidMessageException;
import ch.benedict.m321.batchwriter.persistence.MessageRepository;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holt Pakete aus chat.persist, schreibt sie und entscheidet, was RabbitMQ
 * danach mit jeder Nachricht tun soll: bestätigen oder ablehnen.
 *
 * Die wichtigste Regel dieses Dienstes steht hier: bestätigt wird erst,
 * NACHDEM die Datenbank das Paket committet hat. So geht bei einem Absturz
 * nichts verloren (at-least-once, PLANUNG.md 3.6).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PersistListener {

    private final ChatMessageParser chatMessageParser;
    private final MessageRepository messageRepository;

    /**
     * Eine gelesene Nachricht zusammen mit ihrer Zustellnummer. Die Nummer
     * (delivery tag) braucht es, um genau diese Nachricht zu bestätigen
     * oder abzulehnen.
     */
    private record ReceivedMessage(ChatMessage message, long deliveryTag) {
    }

    /**
     * Wird für jedes Paket einmal aufgerufen (bis 500 Nachrichten oder 200 ms,
     * siehe RabbitConfig). "channel" ist die Leitung zu RabbitMQ, über die
     * wir bestätigen.
     */
    @RabbitListener(
            id = "persistListener",
            queues = QueueNames.PERSIST_QUEUE,
            containerFactory = "batchListenerFactory")
    public void onBatch(List<Message> deliveries, Channel channel) throws IOException {
        log.debug("Received batch of {} deliveries", deliveries.size());

        List<ReceivedMessage> readableMessages = parseAll(deliveries, channel);
        if (readableMessages.isEmpty()) {
            return;
        }
        writeBatch(readableMessages, channel);
    }

    /**
     * Liest jede Zustellung des Pakets. Was unlesbar ist, wird sofort ohne
     * Requeue abgelehnt und landet so in chat.dlq (Spezifikation 3.4).
     * Zurück kommen nur die lesbaren Nachrichten.
     */
    private List<ReceivedMessage> parseAll(List<Message> deliveries, Channel channel) throws IOException {
        List<ReceivedMessage> readableMessages = new ArrayList<>();
        for (Message delivery : deliveries) {
            long deliveryTag = delivery.getMessageProperties().getDeliveryTag();
            try {
                ChatMessage message = chatMessageParser.parse(delivery.getBody());
                ReceivedMessage received = new ReceivedMessage(message, deliveryTag);
                readableMessages.add(received);
            } catch (InvalidMessageException exception) {
                log.warn("Unreadable message {} sent to {}: {}",
                        deliveryTag, QueueNames.DEAD_LETTER_QUEUE, exception.getMessage());
                channel.basicReject(deliveryTag, false);
            }
        }
        return readableMessages;
    }

    /**
     * Schreibt das ganze Paket in einer Transaktion und bestätigt es danach
     * mit EINEM ACK. multiple = true heisst: alles bis und mit dieser Nummer
     * ist erledigt. Die letzte Nachricht im Paket hat die höchste Nummer.
     */
    private void writeBatch(List<ReceivedMessage> readableMessages, Channel channel) throws IOException {
        List<ChatMessage> messages = new ArrayList<>();
        for (ReceivedMessage received : readableMessages) {
            messages.add(received.message());
        }

        messageRepository.insertAll(messages);

        ReceivedMessage last = readableMessages.get(readableMessages.size() - 1);
        channel.basicAck(last.deliveryTag(), true);
        log.info("Batch of {} messages written and acknowledged", messages.size());
    }
}
