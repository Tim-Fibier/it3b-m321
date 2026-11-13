package ch.benedict.m321.chatservice.stream;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.history.MessageHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Öffnet Streams: neu oder als Fortsetzung mit Last-Event-ID.
 *
 * Bei einer Fortsetzung kommen die verpassten Nachrichten aus der Tabelle
 * message. Jeder Fehler, der das verhindert, wird gemeldet, bevor der Stream
 * beginnt, damit der Client einen Statuscode bekommt und nie still eine Lücke
 * entsteht: 400, 409 oder 503.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StreamService {

    /**
     * 0 heisst: kein Zeitlimit. Ein Stream soll offen bleiben, solange der Client
     * zuhört, und nicht nach dem Standard-Zeitlimit des Servers abbrechen.
     */
    private static final long NO_TIMEOUT = 0L;

    /** Text des ersten SSE-Kommentars. Kommentare zeigt ein Client nicht als Nachricht an. */
    private static final String CONNECTED_COMMENT = "verbunden";

    private final StreamRegistry streamRegistry;
    private final MessageHistoryRepository historyRepository;
    private final ObjectMapper objectMapper;

    /**
     * Öffnet einen Stream für einen Raum. Ohne lastEventId ist er sofort live,
     * mit lastEventId holt er zuerst nach.
     *
     * Ein leerer Header zählt wie kein Header: Ein Browser schickt ihn so, wenn
     * er noch keine Nachricht gesehen hat.
     */
    public SseEmitter open(UUID roomId, String lastEventId) throws IOException {
        if (lastEventId == null || lastEventId.isBlank()) {
            SseEmitter emitter = createEmitter();
            streamRegistry.register(roomId, emitter);
            log.info("Stream opened for room {}", roomId);
            return emitter;
        }
        UUID cursor = parseLastEventId(lastEventId);
        return resume(roomId, cursor);
    }

    /**
     * Setzt einen Stream nach der Nachricht cursor fort. Die Reihenfolge der
     * Schritte verhindert Lücken und Dubletten:
     *
     * 1. Position suchen. Gibt es die ID im Raum nicht, ist unklar, wo der Client
     *    steht: 409 statt raten.
     * 2. Verbindung im Modus "holt nach" eintragen. Ab jetzt landet jede Live-
     *    Nachricht im Puffer, keine geht zwischen Lesen und Umschalten verloren.
     * 3. Alles nach der Position lesen. Was jetzt schon gespeichert ist, kommt
     *    aus der Datenbank, was später kommt, steht im Puffer.
     * 4. Beides zusammenführen, Doppelte über die ID entfernen, sortiert senden
     *    und auf live umschalten, in einem Stück.
     *
     * Ist die Datenbank in Schritt 1 oder 3 nicht erreichbar, wird die Verbindung
     * wieder entfernt, und der Client bekommt 503. Er kann es mit derselben
     * Last-Event-ID erneut versuchen.
     */
    private SseEmitter resume(UUID roomId, UUID cursor) throws IOException {
        Instant cursorSentAt = findCursor(roomId, cursor);

        SseEmitter emitter = createEmitter();
        StreamConnection connection = streamRegistry.registerCatchingUp(roomId, emitter);

        List<ChatMessage> missed;
        try {
            missed = historyRepository.findAfter(roomId, cursorSentAt, cursor);
        } catch (DataAccessException exception) {
            streamRegistry.remove(roomId, connection);
            emitter.complete();
            throw databaseUnavailable(exception);
        }

        List<StreamEvent> replay = toEvents(missed);
        connection.catchUp(replay);
        log.info("Stream resumed for room {} after {}, {} messages caught up", roomId, cursor, replay.size());
        return emitter;
    }

    /** Sucht den Zeitpunkt der Nachricht, nach der fortgesetzt wird. */
    private Instant findCursor(UUID roomId, UUID cursor) {
        Optional<Instant> sentAt;
        try {
            sentAt = historyRepository.findSentAt(roomId, cursor);
        } catch (DataAccessException exception) {
            throw databaseUnavailable(exception);
        }
        if (sentAt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Last-Event-ID ist in diesem Raum nicht bekannt");
        }
        return sentAt.get();
    }

    /** Last-Event-ID muss eine Nachrichten-ID sein, also eine UUID. */
    private UUID parseLastEventId(String lastEventId) {
        try {
            return UUID.fromString(lastEventId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Last-Event-ID ist keine UUID");
        }
    }

    /**
     * Macht aus den gelesenen Nachrichten Stream-Ereignisse. Das JSON schreibt
     * derselbe ObjectMapper, der es auch für die Queue schreibt, darum hat
     * "data:" beim Nachholen dasselbe Format wie live.
     */
    private List<StreamEvent> toEvents(List<ChatMessage> messages) throws IOException {
        List<StreamEvent> events = new ArrayList<>();
        for (ChatMessage message : messages) {
            String json = objectMapper.writeValueAsString(message);
            UUID id = message.id();
            Instant sentAt = message.sentAt();
            StreamEvent event = new StreamEvent(id, sentAt, json);
            events.add(event);
        }
        return events;
    }

    /**
     * Ein neuer Emitter, der sofort einen Kommentar schickt. So bekommt der
     * Client die Antwort-Header, ohne auf die erste Nachricht zu warten.
     */
    private SseEmitter createEmitter() throws IOException {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        SseEmitter.SseEventBuilder connected = SseEmitter.event().comment(CONNECTED_COMMENT);
        emitter.send(connected);
        return emitter;
    }

    /** Die Antwort, wenn die Datenbank fehlt: 503, damit der Client es später erneut versucht. */
    private ResponseStatusException databaseUnavailable(DataAccessException exception) {
        log.warn("Database not reachable, stream not resumed: {}", exception.getMessage());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Verlauf nicht erreichbar");
    }
}
