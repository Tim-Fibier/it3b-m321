package ch.bzz.m321.chatservice.dto;

import lombok.Data;

/**
 * Payload für POST /api/chats.
 */
@Data
public class CreateChatRequest {
    private String name;
}
