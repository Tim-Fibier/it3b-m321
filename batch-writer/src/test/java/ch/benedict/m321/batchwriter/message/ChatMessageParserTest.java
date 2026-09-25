package ch.benedict.m321.batchwriter.message;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft den Vertrag mit dem chat-service: kann der batch-writer genau das
 * lesen, was in chat.persist ankommt, und erkennt er, was er nie lesen kann?
 *
 * Reiner Unit-Test ohne Container. Die Beispielnachricht ist eine echte,
 * am 25.09.2026 aus der Queue gelesen (siehe Spezifikation 2.2).
 */
class ChatMessageParserTest {

    /** Wörtlich der Rumpf, den der chat-service in die Queue legt. */
    private static final String REAL_MESSAGE = """
            {"id":"ff47108c-0043-489b-a537-f40dc7e67348",\
            "roomId":"3f2b1c4e-0000-0000-0000-000000000001",\
            "senderId":"anna",\
            "senderName":"Anna Muster",\
            "content":"Hallo Vertrag",\
            "sentAt":"2026-09-25T08:28:43.509872789Z"}""";

    private final ChatMessageParser parser = new ChatMessageParser();

    /** Alle sechs Felder kommen an, auch die Nanosekunden im Zeitstempel. */
    @Test
    void readsRealMessageFromChatService() {
        byte[] body = REAL_MESSAGE.getBytes(StandardCharsets.UTF_8);

        ChatMessage message = parser.parse(body);

        assertEquals(UUID.fromString("ff47108c-0043-489b-a537-f40dc7e67348"), message.id());
        assertEquals(UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001"), message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("Hallo Vertrag", message.content());
        assertEquals(Instant.parse("2026-09-25T08:28:43.509872789Z"), message.sentAt());
    }

    /**
     * Ergänzt der chat-service später ein Feld, darf der batch-writer
     * deshalb nicht stehen bleiben (tolerant lesen).
     */
    @Test
    void ignoresUnknownFields() {
        String json = REAL_MESSAGE.replace("{\"id\"", "{\"neuesFeld\":42,\"id\"");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ChatMessage message = parser.parse(body);

        assertEquals("Hallo Vertrag", message.content());
    }

    /** Kein JSON: wird auch beim zehnten Versuch nicht lesbar. */
    @Test
    void rejectsBodyThatIsNotJson() {
        byte[] body = "kein JSON".getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidMessageException.class, () -> parser.parse(body));
    }

    /** Ein fehlendes Pflichtfeld würde erst beim INSERT auffallen, hier schon früher. */
    @Test
    void rejectsMissingContent() {
        String json = REAL_MESSAGE.replace("\"content\":\"Hallo Vertrag\",", "");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidMessageException.class, () -> parser.parse(body));
    }

    /** Ein Feld im falschen Format ist genauso unlesbar wie ein fehlendes. */
    @Test
    void rejectsRoomIdThatIsNoUuid() {
        String json = REAL_MESSAGE.replace("3f2b1c4e-0000-0000-0000-000000000001", "kein-uuid");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidMessageException.class, () -> parser.parse(body));
    }
}
