package ch.bzz.m321.chatservice.service;

import ch.bzz.m321.chatservice.dto.ChatDTO;
import ch.bzz.m321.chatservice.dto.MessageDTO;
import ch.bzz.m321.chatservice.entity.Chat;
import ch.bzz.m321.chatservice.repository.ChatRepository;
import ch.bzz.m321.chatservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Lese-Zugriff auf Chats und Nachrichten sowie Erstellung neuer Chats.
 * Bewusste Design-Entscheidung: Chat-Metadaten (diese Klasse) werden direkt
 * geschrieben, da sie selten und nicht performancekritisch sind. Einzelne
 * Nachrichten dagegen werden NIE hier geschrieben - das übernimmt
 * ausschliesslich der Batch Service über Redis Pub/Sub (siehe PLANUNG.md).
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;

    public List<ChatDTO> listChats() {
        return chatRepository.findAll().stream()
                .map(this::toChatDTO)
                .toList();
    }

    public ChatDTO createChat(String name) {
        Chat chat = Chat.builder().name(name).build();
        Chat saved = chatRepository.save(chat);
        return toChatDTO(saved);
    }

    public List<MessageDTO> getMessages(Long chatId) {
        return messageRepository.findByChatIdOrderByCreatedAtAsc(chatId).stream()
                .map(m -> MessageDTO.builder()
                        .id(m.getId())
                        .chatId(m.getChatId())
                        .userId(m.getUserId())
                        .content(m.getContent())
                        .createdAt(m.getCreatedAt())
                        .build())
                .toList();
    }

    private ChatDTO toChatDTO(Chat chat) {
        return ChatDTO.builder()
                .id(chat.getId())
                .name(chat.getName())
                .createdAt(chat.getCreatedAt())
                .build();
    }
}
