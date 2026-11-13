package ch.benedict.m321.chatservice.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Führt die offenen Stream-Verbindungen dieser Instanz, getrennt nach Raum.
 *
 * Per Fanout bekommt jede Instanz jede Nachricht. Erst hier entscheidet sich,
 * welche Clients sie sehen: nur die, die den Raum der Nachricht abonniert haben.
 */
@Component
@Slf4j
public class StreamRegistry {

    /**
     * Pro Raum die offenen Verbindungen. Web-Threads fügen hinzu, während der
     * Listener-Thread liest, darum Klassen, die gleichzeitigen Zugriff vertragen.
     */
    private final Map<UUID, Set<SseEmitter>> emittersByRoom = new ConcurrentHashMap<>();

    /** Nimmt eine neue Verbindung in ihren Raum auf. */
    public void register(UUID roomId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByRoom.computeIfAbsent(roomId, key -> new CopyOnWriteArraySet<>());
        emitters.add(emitter);
        log.debug("Stream for room {} registered, {} open", roomId, emitters.size());
    }

    /**
     * Schickt eine Nachricht an alle Verbindungen eines Raums.
     *
     * Ohne Verbindung in diesem Raum passiert nichts: Die Nachricht ist dann für
     * diese Instanz nicht bestimmt. Den Verlauf hat ohnehin die Datenbank.
     */
    public void sendToRoom(UUID roomId, String messageId, String json) {
        Set<SseEmitter> emitters = emittersByRoom.get(roomId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, messageId, json);
        }
    }

    /**
     * Schliesst beim Herunterfahren alle Streams.
     *
     * Spring Boot wartet beim Stoppen bis zu 30 s, bis offene Requests fertig
     * sind. Ein Stream wird nie von selbst fertig. Ohne dieses Schliessen hinge
     * jede Instanz mit offenen Streams so lange, und ihre Live-Queue bliebe
     * stehen. Das Ereignis kommt, bevor der Webserver zu warten beginnt. Die
     * Clients sehen ein sauberes Ende und verbinden sich neu.
     */
    @EventListener(ContextClosedEvent.class)
    public void completeAllOnShutdown() {
        for (Set<SseEmitter> emitters : emittersByRoom.values()) {
            for (SseEmitter emitter : emitters) {
                emitter.complete();
            }
        }
        emittersByRoom.clear();
        log.info("All streams completed for shutdown");
    }

    /**
     * Schreibt ein Ereignis in eine Verbindung. "id:" ist die Nachrichten-ID,
     * damit der Client sie bei einer Neuverbindung als Last-Event-ID zurückschickt.
     *
     * Kein eigenes Sperren nötig: Der Emitter sperrt selbst, wenn zwei Threads
     * gleichzeitig senden. Ein Fehler betrifft nur diese eine Verbindung.
     */
    private void send(SseEmitter emitter, String messageId, String json) {
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(messageId)
                .data(json);
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            log.warn("Stream not writable, message {} dropped for this client: {}",
                    messageId, exception.getMessage());
        }
    }
}
