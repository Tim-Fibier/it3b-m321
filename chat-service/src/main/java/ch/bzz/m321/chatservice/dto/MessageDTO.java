package ch.bzz.m321.chatservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Data Transfer Object für Nachrichten.
 * Wird zwischen REST-API und Frontend ausgetauscht.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageDTO {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("chatId")
    private Long chatId;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("content")
    private String content;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

}
