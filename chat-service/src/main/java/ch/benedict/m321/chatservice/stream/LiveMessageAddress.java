package ch.benedict.m321.chatservice.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Die drei Felder, die der Live-Weg aus einer Nachricht lesen muss: die ID für
 * "id:", den Raum für den Filter und den Zeitpunkt für die Reihenfolge beim
 * Nachholen. Die übrigen Felder im JSON werden beim Lesen übergangen.
 *
 * Bewusst nicht ChatMessage: Der Stream gibt das JSON unverändert weiter. Würde
 * er es ganz einlesen und neu schreiben, könnte sich das Format still ändern.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LiveMessageAddress(UUID id, UUID roomId, Instant sentAt) {
}
