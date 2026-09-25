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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nachrichten, die sich nie schreiben lassen, gehören in chat.dlq, und zwar
 * nur sie, nicht der Rest ihres Pakets (Spezifikation 3.4 und 3.5).
 */
@SpringBootTest
@Testcontainers
// Den Spring-Kontext nach dieser Klasse schliessen. Sonst liefe sein Listener
// weiter und versuchte alle 5 s, den schon gestoppten Container zu erreichen.
@DirtiesContext
class PoisonMessageIntegrationTest {

    /** Die echte Datenbank, die den zu langen Namen ablehnt. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Der echte Broker, der Abgelehntes nach chat.dlq legt. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Leere Queues und eine leere Tabelle, damit jeder Test nur sich selbst zählt. */
    @BeforeEach
    void resetState() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        rabbitAdmin.purgeQueue(QueueNames.DEAD_LETTER_QUEUE);
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Kaputtes JSON wird nie lesbar: sofort in die DLQ, nichts in der Tabelle. */
    @Test
    void sendsUnreadableMessageToDeadLetterQueue() throws InterruptedException {
        publish("das ist kein JSON");

        waitForDeadLetters(1);

        assertEquals(1, countDeadLetters());
        assertEquals(0, countRows());
    }

    /**
     * Zwei gute Nachrichten und eine mit einem Namen, der länger ist als die
     * Spalte erlaubt. Die Datenbank lehnt das ganze Paket ab. Danach müssen
     * die zwei guten trotzdem in der Tabelle stehen und nur die kaputte in
     * der DLQ liegen.
     */
    @Test
    void sendsOnlyThePoisonMessageToDeadLetterQueue() throws InterruptedException {
        String tooLongName = "x".repeat(300);
        publish(createJson("gut 1", "Anna Muster"));
        publish(createJson("kaputt", tooLongName));
        publish(createJson("gut 2", "Anna Muster"));

        waitForDeadLetters(1);
        Thread.sleep(2000);

        assertEquals(2, countRows());
        assertEquals(1, countDeadLetters());
        QueueInformation persistQueue = rabbitAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
        assertEquals(0, persistQueue.getMessageCount());
    }

    /** Baut den Rumpf einer Nachricht im Format des chat-service. */
    private String createJson(String content, String senderName) {
        return "{"
                + "\"id\":\"" + UUID.randomUUID() + "\","
                + "\"roomId\":\"3f2b1c4e-0000-0000-0000-000000000001\","
                + "\"senderId\":\"anna\","
                + "\"senderName\":\"" + senderName + "\","
                + "\"content\":\"" + content + "\","
                + "\"sentAt\":\"" + Instant.now() + "\""
                + "}";
    }

    /** Legt einen beliebigen Rumpf direkt in chat.persist. */
    private void publish(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(bytes, properties);
        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, message);
    }

    /** Wartet höchstens 30 s, bis so viele Nachrichten in chat.dlq liegen. */
    private void waitForDeadLetters(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (countDeadLetters() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
    }

    /** Wie viele Nachrichten in chat.dlq liegen. */
    private int countDeadLetters() {
        QueueInformation deadLetterQueue = rabbitAdmin.getQueueInfo(QueueNames.DEAD_LETTER_QUEUE);
        return deadLetterQueue.getMessageCount();
    }

    /** Zählt die Zeilen in message. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }
}
