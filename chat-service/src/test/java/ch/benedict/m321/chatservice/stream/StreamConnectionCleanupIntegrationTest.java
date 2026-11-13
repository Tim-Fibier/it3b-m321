package ch.benedict.m321.chatservice.stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft, dass getrennte Clients aus der Registry verschwinden und die übrigen
 * Clients davon nichts merken. Ohne Broker: Die Tests schreiben direkt in die
 * Registry. Der Herzschlag läuft hier alle 200 ms statt alle 15 s, damit die
 * Tests nicht lange warten.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "chat-service.stream.heartbeat-millis=200")
class StreamConnectionCleanupIntegrationTest {

    /** So lange wartet ein Test höchstens auf etwas, das eintreten soll. */
    private static final Duration ARRIVES = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private StreamRegistry streamRegistry;

    /** Der Herzschlag kommt als SSE-Kommentar beim Client an. */
    @Test
    void heartbeatReachesClient() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);

        try (SseTestClient client = SseTestClient.connect(uri)) {
            String connected = client.nextComment(ARRIVES);
            String heartbeat = client.nextComment(ARRIVES);

            assertEquals("verbunden", connected);
            assertEquals("ping", heartbeat);
        }
    }

    /**
     * Ein Client trennt. Spätestens beim Herzschlag merkt der Server das und
     * führt ihn nicht mehr; der zweite Client im selben Raum bekommt weiter
     * seine Nachrichten.
     */
    @Test
    void disconnectedClientIsRemoved() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);

        try (SseTestClient staying = SseTestClient.connect(uri)) {
            SseTestClient leaving = SseTestClient.connect(uri);
            assertEquals(2, streamRegistry.countConnections(roomId));

            leaving.close();
            int remaining = waitForConnections(roomId, 1);
            assertEquals(1, remaining);

            StreamEvent sent = TestEvents.withJson("{\"content\":\"weiter\"}");
            UUID sentId = sent.id();
            String messageId = sentId.toString();
            streamRegistry.sendToRoom(roomId, sent);
            SseEvent event = staying.nextEvent(ARRIVES);
            assertNotNull(event);
            assertEquals(messageId, event.id());
        }
    }

    /**
     * Ein Fehler beim Schreiben an einen getrennten Client betrifft nur ihn:
     * Direkt nach dem Trennen, noch vor einem Herzschlag, gehen 20 Nachrichten
     * raus, und der andere Client bekommt alle 20.
     */
    @Test
    void writeErrorDoesNotDisturbOtherClients() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);

        try (SseTestClient staying = SseTestClient.connect(uri)) {
            SseTestClient leaving = SseTestClient.connect(uri);
            leaving.close();

            List<String> sentIds = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                StreamEvent sent = TestEvents.withJson("{\"content\":\"" + i + "\"}");
                UUID sentId = sent.id();
                String messageId = sentId.toString();
                sentIds.add(messageId);
                streamRegistry.sendToRoom(roomId, sent);
            }

            for (String expectedId : sentIds) {
                SseEvent event = staying.nextEvent(ARRIVES);
                assertNotNull(event, "Nachricht " + expectedId + " fehlt");
                assertEquals(expectedId, event.id());
            }
        }
    }

    /** Wartet, bis die Registry im Raum so viele Verbindungen führt, und gibt die Zahl zurück. */
    private int waitForConnections(UUID roomId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ARRIVES.toMillis();
        int count = streamRegistry.countConnections(roomId);
        while (count != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            count = streamRegistry.countConnections(roomId);
        }
        return count;
    }

    /** Baut die Adresse des Streams für einen Raum auf dem zufälligen Port des Tests. */
    private URI streamUri(UUID roomId) {
        return URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
    }
}
