package ch.benedict.m321.chatservice.stream;

import ch.benedict.m321.chatservice.config.QueueNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der ganze Live-Weg gegen einen echten RabbitMQ: POST /messages, Fanout an die
 * Live-Queue, Listener, Stream. Erst dieser Test zeigt, dass alle Teile
 * zusammenpassen.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LiveDeliveryIntegrationTest {

    /** So lange wartet ein Test höchstens auf etwas, das ankommen soll. */
    private static final Duration ARRIVES = Duration.ofSeconds(5);

    /** So lange schaut ein Test, ob etwas ankommt, das nicht ankommen darf. */
    private static final Duration STAYS_AWAY = Duration.ofSeconds(1);

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /** Liest das JSON aus "data:", um die Felder zu prüfen. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** chat.persist überlebt die einzelne Testmethode, darum vor jedem Test leeren. */
    @BeforeEach
    void emptyPersistQueue() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
    }

    /**
     * Eine Nachricht für Raum A kommt beim Client in Raum A an: "id:" ist die
     * vergebene ID, "data:" hat alle sechs Felder und ist Zeichen für Zeichen
     * dasselbe JSON wie auf chat.persist.
     */
    @Test
    void deliversPostedMessageToClientInRoom() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);

        try (SseTestClient client = SseTestClient.connect(uri)) {
            String messageId = MessageSender.send(port, roomId, "anna", "Hallo live");

            SseEvent event = client.nextEvent(ARRIVES);
            assertNotNull(event);
            assertEquals(messageId, event.id());
            assertHasAllSixFields(event.data());

            Message persisted = rabbitTemplate.receive(QueueNames.PERSIST_QUEUE, 5000);
            assertNotNull(persisted);
            byte[] persistedBody = persisted.getBody();
            String persistedJson = new String(persistedBody, StandardCharsets.UTF_8);
            assertEquals(persistedJson, event.data());
        }
    }

    /** Der Raumfilter über den ganzen Weg: Raum B bekommt nichts aus Raum A. */
    @Test
    void doesNotDeliverToOtherRoom() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        URI uriA = streamUri(roomA);
        URI uriB = streamUri(roomB);

        try (SseTestClient clientA = SseTestClient.connect(uriA);
             SseTestClient clientB = SseTestClient.connect(uriB)) {
            MessageSender.send(port, roomA, "anna", "nur fuer A");

            SseEvent eventA = clientA.nextEvent(ARRIVES);
            SseEvent eventB = clientB.nextEvent(STAYS_AWAY);
            assertNotNull(eventA);
            assertNull(eventB);
        }
    }

    /**
     * Eine unlesbare Nachricht auf chat.delivery darf die Zustellung nicht
     * blockieren: Die gültige Nachricht danach kommt trotzdem an.
     */
    @Test
    void skipsUnreadableMessage() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);
        byte[] garbage = "kein JSON".getBytes(StandardCharsets.UTF_8);
        Message unreadable = MessageBuilder.withBody(garbage).build();

        try (SseTestClient client = SseTestClient.connect(uri)) {
            rabbitTemplate.send(QueueNames.DELIVERY_EXCHANGE, "", unreadable);
            String messageId = MessageSender.send(port, roomId, "anna", "nach dem Muell");

            SseEvent event = client.nextEvent(ARRIVES);
            assertNotNull(event);
            assertEquals(messageId, event.id());
        }
    }

    /** Prüft, dass das JSON alle Felder des Vertrags enthält. */
    private void assertHasAllSixFields(String json) throws Exception {
        JsonNode message = objectMapper.readTree(json);
        String[] fields = {"id", "roomId", "senderId", "senderName", "content", "sentAt"};
        for (String field : fields) {
            boolean present = message.hasNonNull(field);
            assertTrue(present, "Feld fehlt: " + field);
        }
    }

    /** Baut die Adresse des Streams für einen Raum auf dem zufälligen Port des Tests. */
    private URI streamUri(UUID roomId) {
        return URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
    }
}
