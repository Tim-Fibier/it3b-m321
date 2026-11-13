package ch.benedict.m321.chatservice.history;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Liest den Chat-Verlauf aus der Tabelle message, die der batch-writer füllt.
 *
 * Gebraucht für die Neuverbindung mit Last-Event-ID: Die Tabelle ist der einzige
 * Ort, den jede Instanz lesen kann, auch wenn der Client die Instanz wechselt.
 * Der chat-service liest nur; das Schema gehört dem batch-writer.
 */
@Repository
@RequiredArgsConstructor
public class MessageHistoryRepository {

    /** Der Zeitpunkt einer Nachricht, aber nur, wenn sie in diesem Raum liegt. */
    private static final String FIND_SENT_AT = """
            SELECT sent_at
            FROM message
            WHERE id = ? AND room_id = ?
            """;

    /**
     * Alle Nachrichten eines Raums nach einer Position. Die Position ist das Paar
     * (sent_at, id): Bei gleichem Zeitpunkt entscheidet die ID, so ist die
     * Reihenfolge immer eindeutig. Die Nachricht an der Position selbst gehört
     * nicht dazu, die hat der Client schon.
     */
    private static final String FIND_AFTER = """
            SELECT id, room_id, sender_id, sender_name, content, sent_at
            FROM message
            WHERE room_id = ? AND (sent_at, id) > (?, ?)
            ORDER BY sent_at, id
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Sucht den Zeitpunkt einer Nachricht in einem Raum. Leer, wenn es sie dort
     * nicht gibt: unbekannt oder aus einem anderen Raum. Dann lässt sich nicht
     * sagen, wo der Client weitermachen muss.
     */
    public Optional<Instant> findSentAt(UUID roomId, UUID messageId) {
        List<Instant> found = jdbcTemplate.query(FIND_SENT_AT, this::readSentAt, messageId, roomId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Instant sentAt = found.get(0);
        return Optional.of(sentAt);
    }

    /**
     * Liefert alle Nachrichten des Raums, die nach der Position (sentAt, messageId)
     * kommen, aufsteigend sortiert. sentAt muss der Wert aus findSentAt sein.
     */
    public List<ChatMessage> findAfter(UUID roomId, Instant sentAt, UUID messageId) {
        OffsetDateTime position = OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC);
        return jdbcTemplate.query(FIND_AFTER, this::readMessage, roomId, position, messageId);
    }

    /** Liest sent_at aus einer Zeile. */
    private Instant readSentAt(ResultSet row, int rowNumber) throws SQLException {
        OffsetDateTime sentAt = row.getObject("sent_at", OffsetDateTime.class);
        return sentAt.toInstant();
    }

    /** Baut aus einer Zeile dieselbe Nachricht, die auf der Queue lag. */
    private ChatMessage readMessage(ResultSet row, int rowNumber) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        UUID roomId = row.getObject("room_id", UUID.class);
        String senderId = row.getString("sender_id");
        String senderName = row.getString("sender_name");
        String content = row.getString("content");
        Instant sentAt = readSentAt(row, rowNumber);
        return new ChatMessage(id, roomId, senderId, senderName, content, sentAt);
    }
}
