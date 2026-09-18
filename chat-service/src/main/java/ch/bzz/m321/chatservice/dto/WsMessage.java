package ch.bzz.m321.chatservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Eingehende WebSocket-Nachricht vom Browser-Client.
 * type "authenticate" -> Feld token wird ausgewertet.
 * type "message"      -> Felder chatId und content werden ausgewertet.
 * Ein clientseitig mitgeschicktes userId-Feld wird bewusst ignoriert;
 * massgeblich ist die Benutzer-ID aus dem validierten Token.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WsMessage {
    private String type;
    private String token;
    private Long chatId;
    private String content;
}
