package ch.benedict.m321.batchwriter.message;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, wie sie in chat.persist ankommt.
 *
 * Das ist eine eigene Kopie, nicht die Klasse aus dem chat-service. Der
 * Vertrag zwischen den beiden Diensten ist das JSON, nicht eine Java-Klasse:
 * ein gemeinsames Modul würde die Dienste aneinanderbinden (siehe den
 * Kommentar in chat-service/.../dto/ChatMessage.java).
 *
 * @param id         vom chat-service vergeben, Primärschlüssel in der Tabelle
 * @param roomId     der Raum der Nachricht
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename des Absenders
 * @param content    der Text der Nachricht
 * @param sentAt     der Zeitpunkt, den der chat-service gesetzt hat
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
