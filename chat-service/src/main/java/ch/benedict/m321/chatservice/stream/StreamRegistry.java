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
    private final Map<UUID, Set<StreamConnection>> connectionsByRoom = new ConcurrentHashMap<>();

    /** Nimmt eine neue Verbindung auf, die sofort live ist. */
    public StreamConnection register(UUID roomId, SseEmitter emitter) {
        StreamConnection connection = new StreamConnection(emitter, false);
        add(roomId, connection);
        return connection;
    }

    /**
     * Nimmt eine Verbindung auf, die zuerst nachholt. Ab jetzt werden Live-
     * Nachrichten für sie gepuffert. Darum muss das geschehen, bevor die
     * Datenbank gelesen wird: Was danach kommt, ist dann sicher im Puffer.
     */
    public StreamConnection registerCatchingUp(UUID roomId, SseEmitter emitter) {
        StreamConnection connection = new StreamConnection(emitter, true);
        add(roomId, connection);
        return connection;
    }

    /** Nimmt eine Verbindung aus ihrem Raum; ist der Raum danach leer, verschwindet er ganz. */
    public void remove(UUID roomId, StreamConnection connection) {
        connectionsByRoom.computeIfPresent(roomId, (key, connections) -> withoutConnection(connections, connection));
        log.debug("Stream for room {} removed", roomId);
    }

    /** Wie viele Verbindungen in einem Raum offen sind. Zeigt in Tests, ob aufgeräumt wurde. */
    public int countConnections(UUID roomId) {
        Set<StreamConnection> connections = connectionsByRoom.get(roomId);
        if (connections == null) {
            return 0;
        }
        return connections.size();
    }

    /**
     * Gibt eine Nachricht an alle Verbindungen eines Raums. Ohne Verbindung in
     * diesem Raum passiert nichts: Die Nachricht ist dann für diese Instanz
     * nicht bestimmt. Den Verlauf hat ohnehin die Datenbank.
     */
    public void sendToRoom(UUID roomId, StreamEvent event) {
        Set<StreamConnection> connections = connectionsByRoom.get(roomId);
        if (connections == null) {
            return;
        }
        for (StreamConnection connection : connections) {
            try {
                connection.deliver(event);
            } catch (IOException | IllegalStateException exception) {
                dropBroken(roomId, connection, exception);
            }
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
        for (Map.Entry<UUID, Set<StreamConnection>> room : connectionsByRoom.entrySet()) {
            UUID roomId = room.getKey();
            Set<StreamConnection> connections = room.getValue();
            for (StreamConnection connection : connections) {
                try {
                    connection.sendComment(HEARTBEAT_COMMENT);
                } catch (IOException | IllegalStateException exception) {
                    dropBroken(roomId, connection, exception);
                }
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
        for (Set<StreamConnection> connections : connectionsByRoom.values()) {
            for (StreamConnection connection : connections) {
                SseEmitter emitter = connection.emitter();
                emitter.complete();
            }
        }
        connectionsByRoom.clear();
        log.info("All streams completed for shutdown");
    }

    /**
     * Trägt eine Verbindung in ihren Raum ein und sorgt dafür, dass sie wieder
     * verschwindet, sobald sie endet: regulär, durch Zeitablauf oder durch einen
     * Fehler. Sonst sammelten sich tote Verbindungen an.
     */
    private void add(UUID roomId, StreamConnection connection) {
        SseEmitter emitter = connection.emitter();
        emitter.onCompletion(() -> remove(roomId, connection));
        emitter.onTimeout(() -> remove(roomId, connection));
        emitter.onError(error -> remove(roomId, connection));

        // compute arbeitet pro Raum atomar. Ohne das könnte eine neue Verbindung
        // in einer Menge landen, die ein anderer Thread gerade als leer entfernt.
        connectionsByRoom.compute(roomId, (key, connections) -> withConnection(connections, connection));
        log.debug("Stream for room {} registered", roomId);
    }

    /**
     * Ein Schreiben ist fehlgeschlagen, der Client ist also weg. Die Verbindung
     * wird entfernt; die übrigen Clients bekommen ihr Ereignis trotzdem.
     */
    private void dropBroken(UUID roomId, StreamConnection connection, Exception exception) {
        log.debug("Stream for room {} not writable, removed: {}", roomId, exception.getMessage());
        remove(roomId, connection);
    }

    /** Die Menge eines Raums mit der neuen Verbindung; legt sie beim ersten Mal an. */
    private static Set<StreamConnection> withConnection(Set<StreamConnection> connections,
                                                        StreamConnection connection) {
        Set<StreamConnection> result = connections;
        if (result == null) {
            result = new CopyOnWriteArraySet<>();
        }
        result.add(connection);
        return result;
    }

    /** Die Menge ohne die Verbindung; null, wenn sie leer ist, damit der Raum verschwindet. */
    private static Set<StreamConnection> withoutConnection(Set<StreamConnection> connections,
                                                           StreamConnection connection) {
        connections.remove(connection);
        if (connections.isEmpty()) {
            return null;
        }
        return connections;
    }
}
