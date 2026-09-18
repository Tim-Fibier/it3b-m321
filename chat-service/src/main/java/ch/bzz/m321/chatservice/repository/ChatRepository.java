package ch.bzz.m321.chatservice.repository;

import ch.bzz.m321.chatservice.entity.Chat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepository extends JpaRepository<Chat, Long> {
}
