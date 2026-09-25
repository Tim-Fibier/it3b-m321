package ch.benedict.m321.batchwriter.listener;

import ch.benedict.m321.batchwriter.config.QueueNames;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ListenerContainerConsumerFailedEvent;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenario S7: die Datenbank ist eine Weile nicht erreichbar.
 *
 * Erwartet (Spezifikation 3.3): nichts geht verloren, nichts landet in der
 * DLQ, und der batch-writer fängt den Ausfall selbst ab, statt dass sein
 * Consumer abstürzt und von Spring neu gestartet werden muss.
 *
 * Der Ausfall ist echt, aber ohne den Container zu stoppen: ein neu
 * gestarteter Container bekäme einen neuen Port, und die Anwendung würde
 * ihn nie wiederfinden. Stattdessen sperrt der Test die Datenbank für alle
 * Verbindungen und beendet die bestehenden. Für den batch-writer sieht das
 * aus wie ein gestopptes Postgres: jede Verbindung wird abgewiesen.
 */
@SpringBootTest(properties = {
        // Kürzer als im Betrieb, damit der Test nicht minutenlang dauert.
        "batch-writer.retry-pause-ms=500",
        "spring.datasource.hikari.connection-timeout=1000"
})
@Testcontainers
// Den Spring-Kontext nach dieser Klasse schliessen. Sonst liefe sein Listener
// weiter und versuchte alle 5 s, den schon gestoppten Container zu erreichen.
@DirtiesContext
class DatabaseOutageIntegrationTest {

    /** Die echte Datenbank, die der Test sperrt und wieder freigibt. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Der echte Broker: hier warten die Nachrichten während des Ausfalls. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /**
     * Zählt, wie oft ein Consumer mit einer Ausnahme abgestürzt ist. Spring
     * AMQP meldet jeden solchen Absturz mit diesem Ereignis und startet den
     * Consumer dann neu. Genau das soll beim Datenbankausfall NICHT passieren.
     */
    @TestConfiguration
    static class ConsumerFailureCounter {

        static final AtomicInteger FAILURES = new AtomicInteger();

        /** Meldet sich bei Spring als Empfänger für Consumer-Abstürze an. */
        @Bean
        ApplicationListener<ListenerContainerConsumerFailedEvent> consumerFailureListener() {
            return event -> FAILURES.incrementAndGet();
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /**
     * 300 Nachrichten kommen an, während die Datenbank weg ist. Solange sie
     * weg ist, darf nichts in der DLQ landen. Kommt sie zurück, stehen alle
     * 300 in der Tabelle, ohne dass jemand den Dienst neu startet.
     */
    @Test
    void keepsMessagesDuringOutageAndWritesThemAfterwards() throws Exception {
        blockDatabase();
        for (int i = 0; i < 300; i++) {
            publishChatMessage("Nachricht " + i);
        }

        // Mehrere Runden aus Versuch, Fehler, Pause laufen lassen.
        Thread.sleep(8000);
        assertEquals(0, countDeadLetters(), "Während des Ausfalls gehört nichts in die DLQ");

        unblockDatabase();
        waitForRows(300);

        assertEquals(300, countRows());
        assertEquals(0, countDeadLetters());
        QueueInformation persistQueue = rabbitAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
        assertEquals(0, persistQueue.getMessageCount());
        MessageListenerContainer listener = listenerRegistry.getListenerContainer("persistListener");
        assertTrue(listener.isRunning());
        assertEquals(0, ConsumerFailureCounter.FAILURES.get(), "Der Consumer darf nicht abstürzen");
    }

    /**
     * Sperrt die Datenbank der Anwendung für neue Verbindungen und beendet
     * alle bestehenden. Das geht nur über eine ANDERE Datenbank (postgres),
     * mit dem Superuser des Test-Containers.
     */
    private void blockDatabase() throws SQLException {
        String databaseName = postgres.getDatabaseName();
        try (Connection admin = openAdminConnection();
             Statement statement = admin.createStatement()) {
            statement.execute("ALTER DATABASE " + databaseName + " WITH ALLOW_CONNECTIONS false");
        }
        String terminateSql = "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                + "WHERE datname = ? AND pid <> pg_backend_pid()";
        try (Connection admin = openAdminConnection();
             PreparedStatement statement = admin.prepareStatement(terminateSql)) {
            statement.setString(1, databaseName);
            statement.execute();
        }
    }

    /** Gibt die Datenbank wieder frei: das Ende des Ausfalls. */
    private void unblockDatabase() throws SQLException {
        String databaseName = postgres.getDatabaseName();
        try (Connection admin = openAdminConnection();
             Statement statement = admin.createStatement()) {
            statement.execute("ALTER DATABASE " + databaseName + " WITH ALLOW_CONNECTIONS true");
        }
    }

    /** Eine Verbindung zur Verwaltungs-Datenbank "postgres" im selben Container. */
    private Connection openAdminConnection() throws SQLException {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/postgres";
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    /** Legt eine Nachricht im Format des chat-service in chat.persist. */
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
        Message message = new Message(body, properties);
        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, message);
    }

    /**
     * Wartet höchstens 60 s, bis so viele Zeilen in message stehen. Solange
     * die Verbindung noch nicht wieder klappt, zählt ein Fehler als 0 Zeilen.
     */
    private void waitForRows(int expectedRows) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (countRowsOrZero() < expectedRows && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
        }
    }

    /** Zählt die Zeilen, oder 0, wenn die Datenbank gerade nicht antwortet. */
    private int countRowsOrZero() {
        try {
            return countRows();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /** Zählt die Zeilen in message. */
    private int countRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        return count;
    }

    /** Wie viele Nachrichten in chat.dlq liegen. */
    private int countDeadLetters() {
        QueueInformation deadLetterQueue = rabbitAdmin.getQueueInfo(QueueNames.DEAD_LETTER_QUEUE);
        return deadLetterQueue.getMessageCount();
    }
}
