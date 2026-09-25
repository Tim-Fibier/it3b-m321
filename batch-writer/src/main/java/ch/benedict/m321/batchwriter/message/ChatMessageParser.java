package ch.benedict.m321.batchwriter.message;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Liest den Rumpf einer Nachricht aus chat.persist als JSON.
 *
 * Warum selbst lesen und nicht den JSON-Konverter von Spring AMQP nehmen?
 * Der Konverter richtet sich nach dem Header __TypeId__. Der nennt aber eine
 * Klasse des chat-service, die es hier nicht gibt, und in Szenario S5 fehlt
 * er ganz. Der Rumpf ist der Vertrag, also lesen wir den Rumpf
 * (Spezifikation 2.2, Entscheid E1).
 */
@Component
public class ChatMessageParser {

    /**
     * Sichtbar eingestellt statt von Spring übernommen, damit man hier
     * nachlesen kann, was gilt:
     * JavaTimeModule liest "2026-09-25T08:28:43.509872789Z" als Instant;
     * unbekannte Felder werden ignoriert, damit ein neues Feld im
     * chat-service den batch-writer nicht stoppt.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * Macht aus dem Rumpf eine ChatMessage.
     *
     * @throws InvalidMessageException wenn der Rumpf kein passendes JSON ist
     *                                 oder eines der sechs Felder fehlt
     */
    public ChatMessage parse(byte[] body) {
        ChatMessage message;
        try {
            message = objectMapper.readValue(body, ChatMessage.class);
        } catch (IOException exception) {
            throw new InvalidMessageException("body is not a valid chat message: " + exception.getMessage(), exception);
        }

        if (message == null) {
            throw new InvalidMessageException("body is empty or the JSON value null");
        }
        requireAllFields(message);
        return message;
    }

    /**
     * Jackson setzt fehlende Felder eines Records einfach auf null. Das würde
     * erst beim INSERT als Fehler auffallen, mitten in einem Paket. Hier
     * fällt es früher auf, und nur für diese eine Nachricht.
     */
    private void requireAllFields(ChatMessage message) {
        if (message.id() == null) {
            throw new InvalidMessageException("field id is missing");
        }
        if (message.roomId() == null) {
            throw new InvalidMessageException("field roomId is missing");
        }
        if (message.senderId() == null) {
            throw new InvalidMessageException("field senderId is missing");
        }
        if (message.senderName() == null) {
            throw new InvalidMessageException("field senderName is missing");
        }
        if (message.content() == null) {
            throw new InvalidMessageException("field content is missing");
        }
        if (message.sentAt() == null) {
            throw new InvalidMessageException("field sentAt is missing");
        }
    }
}
