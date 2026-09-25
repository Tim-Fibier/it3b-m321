package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Szenario S5: dieselbe Nachricht kommt zweimal an.
 *
 * Genau so, wie die Lehrperson prüft: im Format des chat-service, direkt
 * in chat.persist gelegt, und NUR mit dem Header content_type. Es fehlt also
 * der Header __TypeId__, den der chat-service sonst mitschickt.
 */
@SpringBootTest
@Testcontainers
class DuplicateMessageIntegrationTest {

    /** Die echte Datenbank: hier entscheidet ON CONFLICT über das Duplikat. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Der echte Broker mit chat.persist und chat.dlq. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Leere Queues und eine leere Tabelle, damit nur dieser Test zählt. */
    @BeforeEach
    void resetState() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE);
        jdbcTemplate.update("DELETE FROM message");
    }

    /**
     * Zweimal dieselbe Nachricht: genau eine Zeile, nichts in der DLQ,
     * chat.persist leer. Ein Duplikat ist bei at-least-once kein Fehler.
     */
    @Test
    void writesDuplicateOnlyOnceAndNothingGoesToDeadLetterQueue() throws InterruptedException {
        UUID messageId = UUID.randomUUID();
        String json = "{"
                + "\"id\":\"" + messageId + "\","
                + "\"roomId\":\"3f2b1c4e-0000-0000-0000-000000000001\","
                + "\"senderId\":\"anna\","
                + "\"senderName\":\"Anna Muster\","
                + "\"content\":\"Zweimal gesendet\","
                + "\"sentAt\":\"2026-09-25T08:28:43.509872789Z\""
                + "}";

        publishWithContentTypeOnly(json);
        publishWithContentTypeOnly(json);

        waitUntilQueueIsProcessed();

        String sql = "SELECT count(*) FROM message WHERE id = ?";
        Integer rows = jdbcTemplate.queryForObject(sql, Integer.class, messageId);
        assertEquals(1, rows);
        QueueInformation deadLetterQueue = rabbitAdmin.getQueueInfo(QueueNames.DEAD_LETTER_QUEUE);
        assertEquals(0, deadLetterQueue.getMessageCount());
        QueueInformation persistQueue = rabbitAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
        assertEquals(0, persistQueue.getMessageCount());
    }

    /**
     * Legt JSON direkt in chat.persist, über den Standard-Exchange, mit
     * content_type als einziger Eigenschaft. Kein __TypeId__.
     */
    private void publishWithContentTypeOnly(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(body, properties);
        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, message);
    }

    /**
     * Wartet, bis in message eine Zeile steht, und dann noch ein paar
     * Sekunden, damit auch die zweite Zustellung sicher verarbeitet ist.
     * Sonst könnte der Test grün sein, nur weil die zweite noch unterwegs ist.
     */
    private void waitUntilQueueIsProcessed() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        while (rows == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            rows = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        }
        Thread.sleep(3000);
    }
}
