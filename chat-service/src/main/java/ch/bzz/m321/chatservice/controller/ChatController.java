package ch.bzz.m321.chatservice.controller;

import ch.bzz.m321.chatservice.dto.ChatDTO;
import ch.bzz.m321.chatservice.dto.CreateChatRequest;
import ch.bzz.m321.chatservice.dto.MessageDTO;
import ch.bzz.m321.chatservice.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST-API für Chats und deren Nachrichtenverlauf.
 * Neue Live-Nachrichten laufen über den WebSocket-Endpoint /ws, nicht hierüber.
 */
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping
    public List<ChatDTO> getChats() {
        return chatService.listChats();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatDTO createChat(@RequestBody CreateChatRequest request) {
        return chatService.createChat(request.getName());
    }

    @GetMapping("/{chatId}/messages")
    public List<MessageDTO> getMessages(@PathVariable Long chatId) {
        return chatService.getMessages(chatId);
    }
}
