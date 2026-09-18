package ch.bzz.m321.batchservice.repository;

import ch.bzz.m321.batchservice.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {
}
