package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.stream.SseEvent;
import ch.benedict.m321.chatservice.stream.SseTestClient;
import ch.benedict.m321.chatservice.stream.StreamRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft GET /stream über echte HTTP-Verbindungen, noch ohne Broker: Die Tests
 * schreiben direkt in die Registry. So lässt sich ein Fehler klar dem Stream
 * zuordnen und nicht dem Weg über RabbitMQ.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamControllerIntegrationTest {

    /** So lange wartet ein Test höchstens auf etwas, das ankommen soll. */
    private static final Duration ARRIVES = Duration.ofSeconds(5);

    /** So lange schaut ein Test, ob etwas ankommt, das nicht ankommen darf. */
    private static final Duration STAYS_AWAY = Duration.ofSeconds(1);

    @LocalServerPort
    private int port;

    @Autowired
    private StreamRegistry streamRegistry;

    /** Ein Stream muss als text/event-stream antworten und sofort den Kommentar schicken. */
    @Test
    void answersWithEventStreamAndConnectedComment() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);

        try (SseTestClient client = SseTestClient.connect(uri)) {
            int status = client.statusCode();
            String contentType = client.contentType();
            String comment = client.nextComment(ARRIVES);

            assertEquals(200, status);
            assertTrue(contentType.startsWith("text/event-stream"));
            assertEquals("verbunden", comment);
        }
    }

    /** Ohne roomId weiss der Server nicht, welche Nachrichten der Client will. */
    @Test
    void rejectsMissingRoomId() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/stream");

        int status = SseTestClient.statusOf(uri);

        assertEquals(400, status);
    }

    /** Eine roomId, die keine UUID ist, kann zu keinem Raum gehören. */
    @Test
    void rejectsRoomIdThatIsNoUuid() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/stream?roomId=raum-a");

        int status = SseTestClient.statusOf(uri);

        assertEquals(400, status);
    }

    /** Was die Registry an den Raum schickt, kommt mit richtigem id: und data: an. */
    @Test
    void deliversEventWithIdAndData() throws Exception {
        UUID roomId = UUID.randomUUID();
        URI uri = streamUri(roomId);
        String messageId = UUID.randomUUID().toString();
        String json = "{\"id\":\"" + messageId + "\",\"content\":\"Hallo\"}";

        try (SseTestClient client = SseTestClient.connect(uri)) {
            streamRegistry.sendToRoom(roomId, messageId, json);

            SseEvent event = client.nextEvent(ARRIVES);
            assertNotNull(event);
            assertEquals(messageId, event.id());
            assertEquals(json, event.data());
        }
    }

    /** Der Raumfilter: Ein Client in Raum B bekommt keine Nachricht aus Raum A. */
    @Test
    void doesNotDeliverToOtherRoom() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        URI uriA = streamUri(roomA);
        URI uriB = streamUri(roomB);
        String messageId = UUID.randomUUID().toString();

        try (SseTestClient clientA = SseTestClient.connect(uriA);
             SseTestClient clientB = SseTestClient.connect(uriB)) {
            streamRegistry.sendToRoom(roomA, messageId, "{\"content\":\"nur fuer A\"}");

            SseEvent eventA = clientA.nextEvent(ARRIVES);
            SseEvent eventB = clientB.nextEvent(STAYS_AWAY);
            assertNotNull(eventA);
            assertNull(eventB);
        }
    }

    /** Baut die Adresse des Streams für einen Raum auf dem zufälligen Port des Tests. */
    private URI streamUri(UUID roomId) {
        return URI.create("http://localhost:" + port + "/stream?roomId=" + roomId);
    }
}
