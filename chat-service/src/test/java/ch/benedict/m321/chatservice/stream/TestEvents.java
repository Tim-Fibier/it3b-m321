package ch.benedict.m321.chatservice.stream;

import java.time.Instant;
import java.util.UUID;

/**
 * Baut Stream-Ereignisse für Tests, die direkt in die Registry schreiben und
 * dafür keine echte Nachricht über RabbitMQ brauchen.
 */
public final class TestEvents {

    /** Reine Hilfsklasse, wird nie erzeugt. */
    private TestEvents() {
    }

    /** Ein Ereignis mit neuer ID, dem aktuellen Zeitpunkt und diesem JSON. */
    public static StreamEvent withJson(String json) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new StreamEvent(id, now, json);
    }
}
