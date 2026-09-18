package ch.bzz.m321.chatservice.redis;

import ch.bzz.m321.chatservice.handler.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Empfängt Nachrichten vom Redis-Channel "chat.messages" (published von
 * beliebigen chat-service-Instanzen, auch von dieser selbst) und leitet sie
 * an alle lokal verbundenen WebSocket-Clients weiter.
 */
@Component
@RequiredArgsConstructor
public class ChatMessageSubscriber implements MessageListener {

    private final ChatWebSocketHandler webSocketHandler;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        webSocketHandler.broadcast(payload);
    }
}
