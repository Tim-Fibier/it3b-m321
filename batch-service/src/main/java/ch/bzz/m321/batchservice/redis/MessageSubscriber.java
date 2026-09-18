package ch.bzz.m321.batchservice.redis;

import ch.bzz.m321.batchservice.dto.MessageEvent;
import ch.bzz.m321.batchservice.service.MessageBatchWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Empfängt jede neue Chat-Nachricht vom Redis-Channel "chat.messages"
 * und übergibt sie dem Batch-Writer zur gebündelten Persistierung.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final MessageBatchWriter batchWriter;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            MessageEvent event = objectMapper.readValue(payload, MessageEvent.class);
            batchWriter.enqueue(event);
        } catch (Exception e) {
            log.error("Nachricht konnte nicht verarbeitet werden: {}", e.getMessage());
        }
    }
}
