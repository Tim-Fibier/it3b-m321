package ch.bzz.m321.chatservice.repository;

import ch.bzz.m321.chatservice.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Rein lesender Zugriff auf Nachrichten. Geschrieben werden Nachrichten
 * ausschliesslich vom Batch Service (siehe PLANUNG.md).
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByChatIdOrderByCreatedAtAsc(Long chatId);
}
