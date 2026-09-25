package ch.benedict.m321.batchwriter.persistence;

import ch.benedict.m321.batchwriter.message.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft das Schreiben gegen ein echtes PostgreSQL, ohne RabbitMQ im Spiel.
 *
 * Hier entscheidet sich zweierlei: dass ein ganzes Paket in einer
 * Transaktion landet, und dass ein Duplikat keine zweite Zeile erzeugt.
 */
@SpringBootTest
@Testcontainers
// Den Spring-Kontext nach dieser Klasse schliessen. Sonst liefe sein Listener
// weiter und versuchte alle 5 s, den schon gestoppten Container zu erreichen.
@DirtiesContext
class MessageRepositoryIntegrationTest {

    /** Die echte Datenbank, in die geschrieben wird. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Wird für den Start des Dienstes gebraucht, hier aber nicht benutzt. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Die Tabelle ist gemeinsamer Zustand aller Testmethoden. Ohne Leeren
     * zählt der zweite Test die Zeilen des ersten mit.
     */
    @BeforeEach
    void emptyMessageTable() {
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Ein volles Paket mit 500 Nachrichten landet vollständig und richtig in der Tabelle. */
    @Test
    void insertsWholeBatch() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ChatMessage message = createMessage("Nachricht " + i, "Anna Muster");
            messages.add(message);
        }

        messageRepository.insertAll(messages);

        assertEquals(500, countRows());
        ChatMessage first = messages.get(0);
        String sql = "SELECT * FROM message WHERE id = ?";
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, first.id());
        assertEquals(first.roomId(), row.get("room_id"));
        assertEquals("anna", row.get("sender_id"));
        assertEquals("Anna Muster", row.get("sender_name"));
        assertEquals("Nachricht 0", row.get("content"));
    }

    /**
     * Dieselbe Nachricht zweimal im selben Paket: ON CONFLICT DO NOTHING
     * überspringt die zweite Zeile auch innerhalb desselben INSERT.
     */
    @Test
    void ignoresDuplicateInSameBatch() {
        ChatMessage message = createMessage("Hallo", "Anna Muster");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(message);
        messages.add(message);

        messageRepository.insertAll(messages);

        assertEquals(1, countRows());
    }

    /**
     * Dieselbe Nachricht in zwei Paketen: so sieht eine erneute Zustellung
     * nach einem Absturz vor dem ACK aus.
     */
    @Test
    void ignoresDuplicateInLaterBatch() {
        ChatMessage message = createMessage("Hallo", "Anna Muster");
        List<ChatMessage> firstBatch = new ArrayList<>();
        firstBatch.add(message);
        List<ChatMessage> secondBatch = new ArrayList<>();
        secondBatch.add(message);

        messageRepository.insertAll(firstBatch);
        messageRepository.insertAll(secondBatch);

        assertEquals(1, countRows());
    }

    /**
     * Ein Inhalt, den die Datenbank nie annimmt: der Name ist länger als die
     * 255 Zeichen der Spalte. Genau diese Ausnahme erkennt der Listener
     * später als Giftnachricht.
     */
    @Test
    void rejectsSenderNameThatIsTooLong() {
        String tooLongName = "x".repeat(300);
        ChatMessage message = createMessage("Hallo", tooLongName);

        assertThrows(DataIntegrityViolationException.class, () -> messageRepository.insertOne(message));
        assertEquals(0, countRows());
    }

    /** Zählt die Zeilen in message. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }

    /** Baut eine vollständige Nachricht mit neuer ID, damit die Tests kurz bleiben. */
    private ChatMessage createMessage(String content, String senderName) {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "anna", senderName, content, sentAt);
    }
}
