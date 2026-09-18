package ch.bzz.m321.chatservice.redis;

import ch.bzz.m321.chatservice.config.RedisConfig;
import ch.bzz.m321.chatservice.dto.MessageDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Veröffentlicht neue Chat-Nachrichten auf dem Redis-Channel "chat.messages".
 * Der Batch Service abonniert denselben Channel und persistiert die Nachricht;
 * alle chat-service-Instanzen (inkl. dieser) abonnieren ihn ebenfalls, um ihre
 * WebSocket-Clients live zu informieren.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(MessageDTO message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(RedisConfig.CHAT_MESSAGES_CHANNEL, json);
        } catch (Exception e) {
            log.error("Nachricht konnte nicht auf Redis publiziert werden: {}", e.getMessage());
        }
    }
}
