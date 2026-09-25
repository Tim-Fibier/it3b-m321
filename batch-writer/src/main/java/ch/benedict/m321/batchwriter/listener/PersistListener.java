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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Holt Pakete aus chat.persist, schreibt sie und entscheidet, was RabbitMQ
 * danach mit jeder Nachricht tun soll: bestätigen, zurücklegen oder ablehnen.
 *
 * Die wichtigste Regel dieses Dienstes steht hier: bestätigt wird erst,
 * NACHDEM die Datenbank das Paket committet hat. So geht bei einem Absturz
 * nichts verloren (at-least-once, PLANUNG.md 3.6).
 *
 * Fehler werden in zwei Arten getrennt (Spezifikation 3.3 bis 3.5, E6):
 * - Fehler im INHALT (unlesbar, von der Datenbank abgelehnt): diese Nachricht
 *   wird nie schreibbar, also sofort in die Dead-Letter-Queue.
 * - Fehler der UMGEBUNG (Datenbank weg): die Nachricht ist in Ordnung, also
 *   zurück in die Queue und später nochmals, ohne Obergrenze.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PersistListener {

    private final ChatMessageParser chatMessageParser;
    private final MessageRepository messageRepository;

    /** So lange wird nach einem Datenbankfehler gewartet, bevor es weitergeht. */
    @Value("${batch-writer.retry-pause-ms}")
    private long retryPauseMs;

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
     *
     * Die Reihenfolge der beiden catch-Blöcke ist wichtig: zuerst der
     * Inhaltsfehler, danach alles andere als Fehler der Umgebung.
     */
    private void writeBatch(List<ReceivedMessage> readableMessages, Channel channel) throws IOException {
        List<ChatMessage> messages = new ArrayList<>();
        for (ReceivedMessage received : readableMessages) {
            messages.add(received.message());
        }

        try {
            messageRepository.insertAll(messages);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Batch of {} messages rejected by the database, writing one by one: {}",
                    messages.size(), exception.getMessage());
            writeOneByOne(readableMessages, channel);
            return;
        } catch (RuntimeException exception) {
            log.warn("Database not available, batch of {} messages returned to {}: {}",
                    messages.size(), QueueNames.PERSIST_QUEUE, exception.getMessage());
            returnBatchToQueue(readableMessages, channel);
            pauseBeforeRetry();
            return;
        }

        ReceivedMessage last = readableMessages.get(readableMessages.size() - 1);
        channel.basicAck(last.deliveryTag(), true);
        log.info("Batch of {} messages written and acknowledged", messages.size());
    }

    /**
     * Legt das ganze Paket unbestätigt zurück in die Queue (Spezifikation 3.3).
     * requeue = true: RabbitMQ stellt die Nachrichten erneut zu. Sie sind dort
     * sicher, bis die Datenbank wieder da ist.
     */
    private void returnBatchToQueue(List<ReceivedMessage> readableMessages, Channel channel) throws IOException {
        ReceivedMessage last = readableMessages.get(readableMessages.size() - 1);
        channel.basicNack(last.deliveryTag(), true, true);
    }

    /**
     * Der Einzelweg (Spezifikation 3.5): jede Nachricht in ihrer eigenen
     * Transaktion. Ohne ihn würde eine einzige kaputte Nachricht alle anderen
     * im Paket mitreissen. Er ist langsamer, läuft aber nur im Fehlerfall.
     *
     * Fällt die Datenbank ausgerechnet jetzt aus, wird danach einmal gewartet.
     */
    private void writeOneByOne(List<ReceivedMessage> readableMessages, Channel channel) throws IOException {
        boolean databaseProblem = false;
        for (ReceivedMessage received : readableMessages) {
            boolean written = writeSingle(received, channel);
            if (!written) {
                databaseProblem = true;
            }
        }
        if (databaseProblem) {
            pauseBeforeRetry();
        }
    }

    /**
     * Schreibt eine Nachricht und entscheidet nur über sie:
     * geschrieben: bestätigen;
     * von der Datenbank abgelehnt: das ist die Giftnachricht, ohne Requeue
     * ablehnen, RabbitMQ legt sie nach chat.dlq;
     * Datenbank weg: zurück in die Queue.
     *
     * @return false, wenn die Datenbank nicht erreichbar war
     */
    private boolean writeSingle(ReceivedMessage received, Channel channel) throws IOException {
        long deliveryTag = received.deliveryTag();
        try {
            messageRepository.insertOne(received.message());
            channel.basicAck(deliveryTag, false);
            return true;
        } catch (DataIntegrityViolationException exception) {
            log.warn("Message {} can never be written, sent to {}: {}",
                    received.message().id(), QueueNames.DEAD_LETTER_QUEUE, exception.getMessage());
            channel.basicReject(deliveryTag, false);
            return true;
        } catch (RuntimeException exception) {
            log.warn("Database not available, message {} returned to {}: {}",
                    received.message().id(), QueueNames.PERSIST_QUEUE, exception.getMessage());
            channel.basicNack(deliveryTag, false, true);
            return false;
        }
    }

    /**
     * Wartet kurz, bevor das nächste Paket geholt wird. Ohne diese Pause
     * würde der Dienst dieselben Nachrichten in einer engen Schleife immer
     * wieder holen und zurücklegen, solange die Datenbank weg ist.
     */
    private void pauseBeforeRetry() {
        try {
            Thread.sleep(retryPauseMs);
        } catch (InterruptedException exception) {
            // Der Dienst wird gerade beendet: nicht weiter warten, aber das
            // Signal für den Rest des Programms wieder setzen.
            Thread.currentThread().interrupt();
        }
    }
}
