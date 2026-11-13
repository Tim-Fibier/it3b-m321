package ch.benedict.m321.chatservice.stream;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenario S7 gegen echten RabbitMQ und echte PostgreSQL: Ein Client trennt,
 * verpasst 50 Nachrichten und verbindet sich mit Last-Event-ID neu.
 *
 * Den batch-writer spielt der Test selbst: Er holt die Nachrichten aus
 * chat.persist und schreibt sie in die Tabelle message, so wie der echte Dienst.
 * Erst danach verbindet sich der Client neu, wie es die Spezifikation für S7
 * festlegt: Nachgeholt wird, was gespeichert ist.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:message-table.sql"
        })
@Testcontainers
class ReconnectIntegrationTest {

    /** Verpasste Nachrichten, wie in S7 verlangt. */
    private static final int MISSED = 50;

    /** So lange wartet ein Test höchstens auf etwas, das ankommen soll. */
    private static final Duration ARRIVES = Duration.ofSeconds(5);

    /** So lange schaut ein Test, ob nach dem Nachholen noch etwas Doppeltes kommt. */
    private static final Duration STAYS_AWAY = Duration.ofSeconds(1);

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** chat.persist überlebt die einzelne Testmethode, darum vor jedem Test leeren. */
    @BeforeEach
    void emptyPersistQueue() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
    }

    /**
     * S7: Nach der Neuverbindung kommen genau die 50 verpassten Nachrichten, in
     * der Reihenfolge der Datenbank und keine doppelt. Danach geht es live weiter.
     */
    @Test
    void reconnectDeliversExactlyTheMissedMessagesThenLive() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);

        String lastSeenId;
        try (SseTestClient first = SseTestClient.connect(uri)) {
            MessageSender.send(port, roomId, "anna", "vor dem Abbruch");
            SseEvent seen = first.nextEvent(ARRIVES);
            assertNotNull(seen);
            lastSeenId = seen.id();
        }
        persistFromQueue(1);

        List<String> missedIds = new ArrayList<>();
        for (int i = 1; i <= MISSED; i++) {
            String id = MessageSender.send(port, roomId, "anna", "verpasst " + i);
            missedIds.add(id);
        }
        persistFromQueue(MISSED);
        List<String> expectedOrder = idsInDatabaseOrderAfter(roomId, lastSeenId);

        try (SseTestClient resumed = SseTestClient.connect(uri, lastSeenId)) {
            List<String> received = new ArrayList<>();
            for (int i = 0; i < MISSED; i++) {
                SseEvent event = resumed.nextEvent(ARRIVES);
                assertNotNull(event, "Nachgeholte Nachricht " + (i + 1) + " fehlt");
                received.add(event.id());
            }
            Set<String> receivedSet = new HashSet<>(received);
            Set<String> missedSet = new HashSet<>(missedIds);
            assertEquals(missedSet, receivedSet);
            assertEquals(expectedOrder, received);

            SseEvent extra = resumed.nextEvent(STAYS_AWAY);
            assertNull(extra, "Nach dem Nachholen kam noch etwas, also eine Dublette");

            String liveId = MessageSender.send(port, roomId, "anna", "wieder live");
            SseEvent live = resumed.nextEvent(ARRIVES);
            assertNotNull(live);
            assertEquals(liveId, live.id());
        }
    }

    /** Eine ID, die es nicht gibt: unklar, wo der Client steht, also 409 statt raten. */
    @Test
    void unknownLastEventIdIsConflict() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);
        String unknown = UUID.randomUUID().toString();

        int status = SseTestClient.statusOf(uri, unknown);

        assertEquals(409, status);
    }

    /** Eine ID aus einem anderen Raum taugt nicht als Position in diesem Raum: 409. */
    @Test
    void lastEventIdFromOtherRoomIsConflict() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        String idInA = MessageSender.send(port, roomA, "anna", "in A");
        persistFromQueue(1);
        URI uriB = streamUri(roomB);

        int status = SseTestClient.statusOf(uriB, idInA);

        assertEquals(409, status);
    }

    /**
     * Spielt den batch-writer: holt so viele Nachrichten aus chat.persist und
     * schreibt sie in die Tabelle message.
     */
    private void persistFromQueue(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            Message message = rabbitTemplate.receive(QueueNames.PERSIST_QUEUE, 5000);
            assertNotNull(message, "chat.persist hat zu wenig Nachrichten");
            byte[] body = message.getBody();
            ChatMessage chatMessage = objectMapper.readValue(body, ChatMessage.class);
            insert(chatMessage);
        }
    }

    /** Schreibt eine Nachricht so in die Tabelle, wie es der batch-writer tut. */
    private void insert(ChatMessage message) {
        Instant sentAtInstant = message.sentAt();
        OffsetDateTime sentAt = OffsetDateTime.ofInstant(sentAtInstant, ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                message.id(), message.roomId(), message.senderId(), message.senderName(), message.content(), sentAt);
    }

    /** Die IDs nach der Position in der Reihenfolge, in der die Datenbank sie sortiert. */
    private List<String> idsInDatabaseOrderAfter(UUID roomId, String cursorId) {
        UUID cursor = UUID.fromString(cursorId);
        return jdbcTemplate.queryForList(
                "SELECT CAST(id AS TEXT) FROM message WHERE room_id = ? "
                        + "AND (sent_at, id) > (SELECT sent_at, id FROM message WHERE id = ?) "
                        + "ORDER BY sent_at, id",
                String.class, roomId, cursor);
    }

    /** Baut die Adresse des Streams für einen Raum auf dem zufälligen Port des Tests. */
    private URI streamUri(UUID roomId) {
        return URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
    }
}
