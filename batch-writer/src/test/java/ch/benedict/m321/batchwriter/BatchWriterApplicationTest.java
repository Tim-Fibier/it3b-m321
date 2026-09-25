package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft, dass der batch-writer überhaupt hochfährt, und zwar gegen eine
 * ECHTE Datenbank und einen ECHTEN Broker. Beide braucht der Dienst schon
 * beim Start: die Datenbank für das Schema, den Broker für den Listener.
 */
@SpringBootTest
@Testcontainers
// Den Spring-Kontext nach dieser Klasse schliessen. Sonst liefe sein Listener
// weiter und versuchte alle 5 s, den schon gestoppten Container zu erreichen.
@DirtiesContext
class BatchWriterApplicationTest {

    /** Ein leeres PostgreSQL in der Version aus PLANUNG.md 2.1. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Derselbe RabbitMQ wie im docker-compose.yml. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /**
     * Kein Assert nötig: fährt der Spring-Kontext nicht hoch, wirft Spring
     * eine Exception und der Test wird rot.
     */
    @Test
    void contextLoads() {
        // Absichtlich leer, siehe Kommentar über der Methode.
    }
}
