package ch.benedict.m321.chatservice.stream;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Szenario S3: zwei Instanzen des chat-service gegen denselben RabbitMQ.
 *
 * Der Test startet beide Instanzen selbst, jede mit eigener Instanz-ID und damit
 * eigener Live-Queue. Hängt ein Client an Instanz A und einer an Instanz B, muss
 * jeder jede Nachricht seines Raums bekommen, egal über welche Instanz sie kam.
 * Genau das wäre mit einer gemeinsamen Queue nicht so.
 */
@Testcontainers
class TwoInstancesDeliveryIntegrationTest {

    /** Nachrichten pro Instanz, wie in S3 verlangt. */
    private static final int MESSAGES_PER_INSTANCE = 200;

    /** So lange wartet der Test höchstens auf das jeweils nächste Ereignis. */
    private static final Duration ARRIVES = Duration.ofSeconds(10);

    /** So lange schaut der Test, ob beim Client in Raum B etwas ankommt. */
    private static final Duration STAYS_AWAY = Duration.ofSeconds(2);

    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Startet beide Instanzen, nachdem der RabbitMQ-Container läuft. */
    @BeforeAll
    static void startInstances() {
        instanceA = startInstance("instance-a");
        instanceB = startInstance("instance-b");
    }

    /** Fährt beide Instanzen herunter; ihre Live-Queues verschwinden damit. */
    @AfterAll
    static void stopInstances() {
        if (instanceA != null) {
            instanceA.close();
        }
        if (instanceB != null) {
            instanceB.close();
        }
    }

    /**
     * Zwei Clients in Raum A, je einer an jeder Instanz, und ein Client in Raum B.
     * Über jede Instanz gehen 200 Nachrichten für Raum A, jede Instanz mit eigenem
     * Absender. Beide Clients in Raum A bekommen alle 400, keine doppelt, und pro
     * Absender in der Reihenfolge des Sendens. Der Client in Raum B bekommt keine.
     */
    @Test
    void bothClientsInRoomGetEveryMessageExactlyOnce() throws Exception {
        int portA = portOf(instanceA);
        int portB = portOf(instanceB);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        URI streamAtA = streamUri(portA, roomA);
        URI streamAtB = streamUri(portB, roomA);
        URI otherRoomAtA = streamUri(portA, roomB);

        try (SseTestClient clientAtA = SseTestClient.connect(streamAtA);
             SseTestClient clientAtB = SseTestClient.connect(streamAtB);
             SseTestClient clientInRoomB = SseTestClient.connect(otherRoomAtA)) {

            Set<String> sentIds = new HashSet<>();
            for (int i = 1; i <= MESSAGES_PER_INSTANCE; i++) {
                String content = Integer.toString(i);
                String idViaA = MessageSender.send(portA, roomA, "sender-a", content);
                String idViaB = MessageSender.send(portB, roomA, "sender-b", content);
                sentIds.add(idViaA);
                sentIds.add(idViaB);
            }

            int expected = 2 * MESSAGES_PER_INSTANCE;
            List<SseEvent> receivedAtA = collect(clientAtA, expected);
            List<SseEvent> receivedAtB = collect(clientAtB, expected);

            assertEveryMessageOnceInOrder(receivedAtA, sentIds);
            assertEveryMessageOnceInOrder(receivedAtB, sentIds);

            SseEvent wrongRoom = clientInRoomB.nextEvent(STAYS_AWAY);
            assertNull(wrongRoom);
        }
    }

    /**
     * Prüft die Ereignisse eines Clients: genau so viele wie gesendet, jede ID nur
     * einmal und nur gesendete IDs, und pro Absender steigt die laufende Nummer
     * im Text lückenlos an.
     */
    private void assertEveryMessageOnceInOrder(List<SseEvent> events, Set<String> sentIds) throws Exception {
        int expectedCount = sentIds.size();
        int receivedCount = events.size();
        assertEquals(expectedCount, receivedCount);

        Set<String> seenIds = new HashSet<>();
        Map<String, Integer> lastNumberBySender = new HashMap<>();
        for (SseEvent event : events) {
            String id = event.id();
            boolean firstTime = seenIds.add(id);
            assertTrue(firstTime, "Doppelt angekommen: " + id);
            assertTrue(sentIds.contains(id), "Nie gesendet: " + id);

            String data = event.data();
            JsonNode message = objectMapper.readTree(data);
            JsonNode senderNode = message.get("senderId");
            JsonNode contentNode = message.get("content");
            String sender = senderNode.asText();
            int number = Integer.parseInt(contentNode.asText());
            int previous = lastNumberBySender.getOrDefault(sender, 0);
            assertEquals(previous + 1, number, "Reihenfolge verletzt bei " + sender);
            lastNumberBySender.put(sender, number);
        }
    }

    /** Sammelt Ereignisse, bis die erwartete Anzahl da ist oder nichts mehr kommt. */
    private List<SseEvent> collect(SseTestClient client, int expected) throws InterruptedException {
        List<SseEvent> events = new ArrayList<>();
        while (events.size() < expected) {
            SseEvent event = client.nextEvent(ARRIVES);
            if (event == null) {
                break;
            }
            events.add(event);
        }
        return events;
    }

    /**
     * Startet eine Instanz des chat-service wie einen eigenen Container: eigener
     * zufälliger Port, eigene Instanz-ID, derselbe Broker.
     *
     * Die Werte gehen als Kommandozeilen-Argumente hinein, weil diese Vorrang vor
     * application.yml haben. Als blosse Vorgabewerte würden sie dort überschrieben.
     */
    private static ConfigurableApplicationContext startInstance(String instanceId) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ChatServiceApplication.class);
        return builder.run(
                "--server.port=0",
                "--chat-service.instance-id=" + instanceId,
                "--spring.rabbitmq.host=" + rabbitMq.getHost(),
                "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
                "--spring.rabbitmq.username=" + rabbitMq.getAdminUsername(),
                "--spring.rabbitmq.password=" + rabbitMq.getAdminPassword());
    }

    /** Der zufällige Port, den die Instanz beim Start bekommen hat. */
    private static int portOf(ConfigurableApplicationContext instance) {
        Environment environment = instance.getEnvironment();
        String port = environment.getProperty("local.server.port");
        return Integer.parseInt(port);
    }

    /** Baut die Adresse des Streams einer Instanz für einen Raum. */
    private static URI streamUri(int port, UUID roomId) {
        return URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
    }
}
