package ch.benedict.m321.chatservice.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Die zwei Felder, die der Live-Weg aus einer Nachricht lesen muss: die ID für
 * "id:" und den Raum für den Filter. Die übrigen Felder im JSON werden beim
 * Lesen übergangen.
 *
 * Bewusst nicht ChatMessage: Der Stream gibt das JSON unverändert weiter. Würde
 * er es ganz einlesen und neu schreiben, könnte sich das Format still ändern.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LiveMessageAddress(UUID id, UUID roomId) {
}
