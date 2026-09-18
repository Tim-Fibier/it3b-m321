package ch.bzz.m321.chatservice.handler;

import ch.bzz.m321.chatservice.dto.MessageDTO;
import ch.bzz.m321.chatservice.dto.WsMessage;
import ch.bzz.m321.chatservice.redis.ChatMessagePublisher;
import ch.bzz.m321.chatservice.service.TokenValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Nimmt WebSocket-Verbindungen von Browser-Clients entgegen.
 * Erwartet als erste Nachricht {"type":"authenticate","token":"..."}.
 * Neue Chat-Nachrichten werden NICHT direkt gespeichert, sondern auf Redis
 * publiziert - der Batch Service übernimmt die Persistierung.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final TokenValidationService tokenValidationService;
    private final ChatMessagePublisher messagePublisher;

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ConcurrentHashMap<String, String> authenticatedUsers = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        WsMessage incoming;
        try {
            incoming = objectMapper.readValue(message.getPayload(), WsMessage.class);
        } catch (Exception e) {
            log.warn("Ungültige WebSocket-Nachricht von {}: {}", session.getId(), e.getMessage());
            return;
        }

        if (incoming.getType() == null) {
            return;
        }

        switch (incoming.getType()) {
            case "authenticate" -> handleAuthenticate(session, incoming);
            case "message" -> handleChatMessage(session, incoming);
            default -> log.warn("Unbekannter Nachrichtentyp: {}", incoming.getType());
        }
    }

    private void handleAuthenticate(WebSocketSession session, WsMessage incoming) throws Exception {
        String userId = tokenValidationService.extractUserId(incoming.getToken());
        if (userId == null) {
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"Ungueltiges Token\"}"));
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        authenticatedUsers.put(session.getId(), userId);
        sessions.add(session);
        log.info("WebSocket authentifiziert: session={} user={}", session.getId(), userId);
    }

    private void handleChatMessage(WebSocketSession session, WsMessage incoming) {
        String userId = authenticatedUsers.get(session.getId());
        if (userId == null) {
            log.warn("Nachricht von nicht authentifizierter Session {} verworfen", session.getId());
            return;
        }
        if (incoming.getChatId() == null || incoming.getContent() == null || incoming.getContent().isBlank()) {
            return;
        }

        MessageDTO event = MessageDTO.builder()
                .chatId(incoming.getChatId())
                .userId(userId)
                .content(incoming.getContent())
                .createdAt(LocalDateTime.now())
                .build();

        messagePublisher.publish(event);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        authenticatedUsers.remove(session.getId());
    }

    /**
     * Sendet eine Nachricht an alle verbundenen Clients dieser Instanz.
     * Wird vom ChatMessageSubscriber aufgerufen, sobald über Redis eine neue
     * Nachricht empfangen wurde (auch die eigene, zuvor selbst publizierte).
     */
    public void broadcast(String json) {
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (Exception e) {
                log.warn("Broadcast an Session {} fehlgeschlagen: {}", session.getId(), e.getMessage());
            }
        }
    }
}
