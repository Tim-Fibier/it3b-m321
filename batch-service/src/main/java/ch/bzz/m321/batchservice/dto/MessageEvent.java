package ch.bzz.m321.batchservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Nachrichten-Ereignis, wie es der Chat Service auf den Redis-Channel
 * "chat.messages" published.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageEvent {
    private Long chatId;
    private String userId;
    private String content;
    private LocalDateTime createdAt;
}
