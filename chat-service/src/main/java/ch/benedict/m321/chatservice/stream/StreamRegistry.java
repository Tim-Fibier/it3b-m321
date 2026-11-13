package ch.benedict.m321.chatservice.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
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

    /** Text des Herzschlag-Kommentars. Ein Client zeigt Kommentare nicht an. */
    private static final String HEARTBEAT_COMMENT = "ping";

    /**
     * Pro Raum die offenen Verbindungen. Web-Threads fügen hinzu und entfernen,
     * während der Listener-Thread liest, darum Klassen, die gleichzeitigen
     * Zugriff vertragen.
     */
    private final Map<UUID, Set<SseEmitter>> emittersByRoom = new ConcurrentHashMap<>();

    /**
     * Nimmt eine neue Verbindung in ihren Raum auf und sorgt dafür, dass sie
     * wieder verschwindet, sobald sie endet: regulär, durch Zeitablauf oder
     * durch einen Fehler. Sonst sammelten sich tote Verbindungen an.
     */
    public void register(UUID roomId, SseEmitter emitter) {
        emitter.onCompletion(() -> remove(roomId, emitter));
        emitter.onTimeout(() -> remove(roomId, emitter));
        emitter.onError(error -> remove(roomId, emitter));

        // compute arbeitet pro Raum atomar. Ohne das könnte eine neue Verbindung
        // in einer Menge landen, die ein anderer Thread gerade als leer entfernt.
        emittersByRoom.compute(roomId, (key, emitters) -> withEmitter(emitters, emitter));
        log.debug("Stream for room {} registered", roomId);
    }

    /** Nimmt eine Verbindung aus ihrem Raum; ist der Raum danach leer, verschwindet er ganz. */
    public void remove(UUID roomId, SseEmitter emitter) {
        emittersByRoom.computeIfPresent(roomId, (key, emitters) -> withoutEmitter(emitters, emitter));
        log.debug("Stream for room {} removed", roomId);
    }

    /** Wie viele Verbindungen in einem Raum offen sind. Zeigt in Tests, ob aufgeräumt wurde. */
    public int countConnections(UUID roomId) {
        Set<SseEmitter> emitters = emittersByRoom.get(roomId);
        if (emitters == null) {
            return 0;
        }
        return emitters.size();
    }

    /**
     * Schickt eine Nachricht an alle Verbindungen eines Raums. "id:" ist die
     * Nachrichten-ID, damit der Client sie bei einer Neuverbindung als
     * Last-Event-ID zurückschickt.
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
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(messageId)
                    .data(json);
            trySend(roomId, emitter, event);
        }
    }

    /**
     * Schickt regelmässig einen Kommentar an jede Verbindung.
     *
     * Ein Client, der einfach verschwindet, meldet sich nicht ab. Erst wenn der
     * Server etwas schreibt, merkt er, dass niemand mehr zuhört. In einem Raum
     * ohne neue Nachrichten passiert das nur durch diesen Herzschlag. Er hält
     * ausserdem Proxys davon ab, eine stille Verbindung zu schliessen.
     */
    @Scheduled(fixedRateString = "${chat-service.stream.heartbeat-millis}")
    public void sendHeartbeat() {
        for (Map.Entry<UUID, Set<SseEmitter>> room : emittersByRoom.entrySet()) {
            UUID roomId = room.getKey();
            Set<SseEmitter> emitters = room.getValue();
            for (SseEmitter emitter : emitters) {
                SseEmitter.SseEventBuilder heartbeat = SseEmitter.event().comment(HEARTBEAT_COMMENT);
                trySend(roomId, emitter, heartbeat);
            }
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
     * Schreibt in eine Verbindung. Geht das nicht, ist der Client weg: Die
     * Verbindung wird entfernt, und die übrigen Clients bekommen ihr Ereignis
     * trotzdem. Kein eigenes Sperren nötig, der Emitter sperrt selbst, wenn zwei
     * Threads gleichzeitig senden.
     */
    private void trySend(UUID roomId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            log.debug("Stream for room {} not writable, removed: {}", roomId, exception.getMessage());
            remove(roomId, emitter);
        }
    }

    /** Die Menge eines Raums mit der neuen Verbindung; legt sie beim ersten Mal an. */
    private static Set<SseEmitter> withEmitter(Set<SseEmitter> emitters, SseEmitter emitter) {
        Set<SseEmitter> result = emitters;
        if (result == null) {
            result = new CopyOnWriteArraySet<>();
        }
        result.add(emitter);
        return result;
    }

    /** Die Menge ohne die Verbindung; null, wenn sie leer ist, damit der Raum verschwindet. */
    private static Set<SseEmitter> withoutEmitter(Set<SseEmitter> emitters, SseEmitter emitter) {
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            return null;
        }
        return emitters;
    }
}
