package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Weg einmal ganz durch: Nachricht in chat.persist, Zeile in message.
 *
 * Die Nachrichten werden hier so in die Queue gelegt, wie der chat-service
 * es tut: als JSON-Text mit content_type application/json.
 */
@SpringBootTest
@Testcontainers
class PersistListenerIntegrationTest {

    /** Die echte Datenbank, in die der Listener schreibt. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Der echte Broker, aus dem der Listener liest. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** So lange warten wir höchstens, bis alle Nachrichten in der Tabelle stehen (wie S3). */
    private static final long MAX_WAIT_MS = 60_000;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /**
     * Queue und Tabelle sind gemeinsamer Zustand aller Testmethoden. Und der
     * Listener muss laufen, auch wenn ein früherer Test ihn angehalten hat.
     */
    @BeforeEach
    void resetState() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
        jdbcTemplate.update("DELETE FROM message");
        MessageListenerContainer listener = listenerRegistry.getListenerContainer("persistListener");
        listener.start();
    }

    /** Szenario S3 im Kleinen: 1000 Nachrichten rein, 1000 Zeilen raus, Queue leer. */
    @Test
    void writesAllMessagesAndEmptiesQueue() throws InterruptedException {
        for (int i = 0; i < 1000; i++) {
            publishChatMessage("Nachricht " + i);
        }

        waitForRows(1000);

        assertEquals(1000, countRows());
        QueueInformation queue = rabbitAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
        assertEquals(0, queue.getMessageCount());
    }

    /**
     * Szenario S4 im Kleinen: batch-writer angehalten, 1000 Nachrichten
     * warten, dann wieder gestartet. Die Datenbank darf dafür höchstens
     * 100 Transaktionen ausführen. Einzeln wären es 1000.
     */
    @Test
    void writesWaitingMessagesWithFewTransactions() throws InterruptedException {
        MessageListenerContainer listener = listenerRegistry.getListenerContainer("persistListener");
        listener.stop();
        for (int i = 0; i < 1000; i++) {
            publishChatMessage("Nachricht " + i);
        }
        waitForQueueDepth(1000);
        long transactionsBefore = readCommittedTransactions();

        listener.start();
        waitForRows(1000);
        long transactionsAfter = readCommittedTransactions();

        long transactionsUsed = transactionsAfter - transactionsBefore;
        assertEquals(1000, countRows());
        assertTrue(transactionsUsed <= 100, "Transaktionen: " + transactionsUsed);
    }

    /**
     * Legt eine Nachricht im Format des chat-service in chat.persist: über
     * den Standard-Exchange, als JSON-Text, persistent.
     */
    private void publishChatMessage(String content) {
        String json = "{"
                + "\"id\":\"" + UUID.randomUUID() + "\","
                + "\"roomId\":\"3f2b1c4e-0000-0000-0000-000000000001\","
                + "\"senderId\":\"anna\","
                + "\"senderName\":\"Anna Muster\","
                + "\"content\":\"" + content + "\","
                + "\"sentAt\":\"" + Instant.now() + "\""
                + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(body, properties);
        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, message);
    }

    /** Wartet, bis die Tabelle mindestens so viele Zeilen hat, höchstens MAX_WAIT_MS. */
    private void waitForRows(int expectedRows) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        while (countRows() < expectedRows && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
        }
    }

    /** Wartet, bis so viele Nachrichten in chat.persist bereitliegen. */
    private void waitForQueueDepth(int expectedMessages) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        QueueInformation queue = rabbitAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
        while (queue.getMessageCount() < expectedMessages && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            queue = rabbitAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
        }
    }

    /**
     * Wie viele Transaktionen die Datenbank bisher committet hat.
     *
     * PostgreSQL schreibt diese Zähler nicht sofort, sondern etwa einmal pro
     * Sekunde. Deshalb zuerst kurz warten, sonst fehlen die letzten.
     */
    private long readCommittedTransactions() throws InterruptedException {
        Thread.sleep(2000);
        String sql = "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()";
        Long committed = jdbcTemplate.queryForObject(sql, Long.class);
        return committed;
    }

    /** Zählt die Zeilen in message. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }
}
