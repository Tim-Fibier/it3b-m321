package ch.benedict.m321.chatservice.stream;

import ch.benedict.m321.chatservice.config.QueueNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Holt die Nachrichten aus der Live-Queue dieser Instanz und reicht sie an die
 * offenen Streams ihres Raums weiter. Damit ist der Weg vom POST bis zum
 * "data:" geschlossen.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LiveMessageListener {

    private final StreamRegistry streamRegistry;
    private final ObjectMapper objectMapper;

    /**
     * Nimmt eine Nachricht aus der Live-Queue.
     *
     * Der Listener bekommt die rohe AMQP-Nachricht statt eines fertigen Objekts.
     * So geht der Rumpf Byte für Byte als "data:" weiter, genau wie er auf
     * chat.persist liegt, und der Header __TypeId__ spielt keine Rolle.
     *
     * Eine unlesbare Nachricht wird nur geloggt. Die Methode kehrt normal zurück,
     * damit RabbitMQ sie als erledigt betrachtet. Würde sie eine Exception werfen,
     * käme dieselbe Nachricht immer wieder und blockierte alle dahinter.
     */
    @RabbitListener(
            queues = QueueNames.LIVE_QUEUE_PREFIX + "${chat-service.instance-id}",
            containerFactory = "liveListenerContainerFactory")
    public void onMessage(Message message) {
        byte[] body = message.getBody();
        String json = new String(body, StandardCharsets.UTF_8);

        LiveMessageAddress address = readAddress(json);
        if (address == null) {
            return;
        }

        UUID id = address.id();
        String messageId = id.toString();
        UUID roomId = address.roomId();
        streamRegistry.sendToRoom(roomId, messageId, json);
    }

    /**
     * Liest ID und Raum aus dem JSON. Gibt null zurück, wenn das nicht geht,
     * weil ohne beides niemand weiss, wohin die Nachricht gehört.
     */
    private LiveMessageAddress readAddress(String json) {
        LiveMessageAddress address;
        try {
            address = objectMapper.readValue(json, LiveMessageAddress.class);
        } catch (JsonProcessingException exception) {
            log.warn("Unreadable message on live queue skipped: {}", exception.getOriginalMessage());
            return null;
        }
        if (address.id() == null || address.roomId() == null) {
            log.warn("Message without id or roomId on live queue skipped");
            return null;
        }
        return address;
    }
}
