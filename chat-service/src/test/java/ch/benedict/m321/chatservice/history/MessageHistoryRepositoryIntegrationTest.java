package ch.benedict.m321.chatservice.history;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Lesen des Verlaufs gegen eine echte PostgreSQL. Die Sortierung nach
 * (sent_at, id) und der Vergleich von Zeilenwerten passieren in der Datenbank,
 * ein Mock würde davon nichts zeigen.
 *
 * Jeder Test läuft in einer Transaktion, die danach zurückgerollt wird. Die
 * Tabelle entsteht darin jedes Mal neu aus message-table.sql.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MessageHistoryRepository.class)
@Testcontainers
@Sql("/message-table.sql")
class MessageHistoryRepositoryIntegrationTest {

    /** Ein fester Zeitpunkt, damit die Reihenfolge in den Tests klar ist. */
    private static final Instant T0 = Instant.parse("2026-11-13T08:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MessageHistoryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Die Nachrichten nach der Position kommen aufsteigend nach Zeit, auch wenn
     * sie in anderer Reihenfolge eingefügt wurden. Die Position selbst fehlt.
     */
    @Test
    void returnsMessagesAfterCursorInTimeOrder() {
        UUID roomId = UUID.randomUUID();
        UUID cursor = insert(roomId, T0, "0");
        UUID third = insert(roomId, T0.plusSeconds(3), "3");
        UUID first = insert(roomId, T0.plusSeconds(1), "1");
        UUID second = insert(roomId, T0.plusSeconds(2), "2");
        insert(roomId, T0.minusSeconds(1), "vorher");

        List<ChatMessage> after = findAfterCursor(roomId, cursor);

        assertEquals(3, after.size());
        assertEquals(first, after.get(0).id());
        assertEquals(second, after.get(1).id());
        assertEquals(third, after.get(2).id());
    }

    /**
     * Bei gleichem Zeitpunkt entscheidet die ID: Nur was nach der Position kommt,
     * wird geliefert, sortiert nach ID. Ohne die ID im Vergleich ginge bei
     * gleichem Zeitpunkt etwas verloren oder käme doppelt.
     */
    @Test
    void sameSentAtIsResolvedById() {
        UUID roomId = UUID.randomUUID();
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID middle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000003");
        insertWithId(high, roomId, T0, "hoch");
        insertWithId(low, roomId, T0, "tief");
        insertWithId(middle, roomId, T0, "mitte");

        List<ChatMessage> after = findAfterCursor(roomId, middle);

        assertEquals(1, after.size());
        assertEquals(high, after.get(0).id());
    }

    /** Nachrichten anderer Räume gehören nicht in den Verlauf dieses Raums. */
    @Test
    void ignoresOtherRooms() {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        UUID cursor = insert(roomA, T0, "A0");
        UUID nextInA = insert(roomA, T0.plusSeconds(1), "A1");
        insert(roomB, T0.plusSeconds(1), "B1");

        List<ChatMessage> after = findAfterCursor(roomA, cursor);

        assertEquals(1, after.size());
        assertEquals(nextInA, after.get(0).id());
    }

    /** Alle sechs Felder kommen so zurück, wie sie gespeichert wurden. */
    @Test
    void readsAllSixFields() {
        UUID roomId = UUID.randomUUID();
        UUID cursor = insert(roomId, T0, "0");
        Instant sentAt = T0.plusSeconds(1);
        UUID id = insert(roomId, sentAt, "Hallo");

        List<ChatMessage> after = findAfterCursor(roomId, cursor);

        ChatMessage message = after.get(0);
        assertEquals(id, message.id());
        assertEquals(roomId, message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("Hallo", message.content());
        assertEquals(sentAt, message.sentAt());
    }

    /** Eine ID, die es nicht gibt, meldet "nicht gefunden". Daraus wird später 409. */
    @Test
    void unknownIdIsNotFound() {
        UUID roomId = UUID.randomUUID();
        insert(roomId, T0, "0");
        UUID unknown = UUID.randomUUID();

        Optional<Instant> sentAt = repository.findSentAt(roomId, unknown);

        assertTrue(sentAt.isEmpty());
    }

    /** Eine ID aus einem anderen Raum meldet ebenfalls "nicht gefunden". */
    @Test
    void idFromOtherRoomIsNotFound() {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        UUID idInA = insert(roomA, T0, "A0");

        Optional<Instant> sentAt = repository.findSentAt(roomB, idInA);

        assertTrue(sentAt.isEmpty());
    }

    /** Sucht die Position wie später der Stream und liest alles danach. */
    private List<ChatMessage> findAfterCursor(UUID roomId, UUID cursor) {
        Optional<Instant> found = repository.findSentAt(roomId, cursor);
        assertTrue(found.isPresent());
        Instant sentAt = found.get();
        return repository.findAfter(roomId, sentAt, cursor);
    }

    /** Fügt eine Nachricht mit zufälliger ID ein, so wie es der batch-writer tut. */
    private UUID insert(UUID roomId, Instant sentAt, String content) {
        UUID id = UUID.randomUUID();
        insertWithId(id, roomId, sentAt, content);
        return id;
    }

    /** Fügt eine Nachricht mit fester ID ein, wenn der Test die Reihenfolge der IDs braucht. */
    private void insertWithId(UUID id, UUID roomId, Instant sentAt, String content) {
        OffsetDateTime sentAtUtc = OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, roomId, "anna", "Anna Muster", content, sentAtUtc);
    }
}
