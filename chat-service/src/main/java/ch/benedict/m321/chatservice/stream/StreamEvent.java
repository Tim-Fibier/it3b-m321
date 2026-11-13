package ch.benedict.m321.chatservice.stream;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie in einen Stream geht: ID für "id:", Zeitpunkt für die
 * Reihenfolge, JSON für "data:". Gleich, ob sie live aus der Queue kommt oder
 * beim Nachholen aus der Datenbank.
 */
public record StreamEvent(UUID id, Instant sentAt, String json) {

    /**
     * Vergleicht zwei Ereignisse nach (sentAt, id), genau wie die Datenbank
     * sortiert. Die IDs werden als Text verglichen: Das entspricht der
     * byteweisen Ordnung von PostgreSQL. UUID.compareTo rechnet mit Vorzeichen
     * und käme bei gleichem Zeitpunkt manchmal zu einer anderen Reihenfolge.
     */
    public static int compareOrder(StreamEvent first, StreamEvent second) {
        Instant firstSentAt = first.sentAt();
        Instant secondSentAt = second.sentAt();
        int bySentAt = firstSentAt.compareTo(secondSentAt);
        if (bySentAt != 0) {
            return bySentAt;
        }
        UUID firstUuid = first.id();
        UUID secondUuid = second.id();
        String firstId = firstUuid.toString();
        String secondId = secondUuid.toString();
        return firstId.compareTo(secondId);
    }
}
