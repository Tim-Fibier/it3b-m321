package ch.bzz.m321.batchservice.service;

import ch.bzz.m321.batchservice.dto.MessageEvent;
import ch.bzz.m321.batchservice.entity.Message;
import ch.bzz.m321.batchservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sammelt eingehende Nachrichten in einem Puffer und schreibt sie gebündelt
 * in die Datenbank (siehe application.yml: batch.size / batch.flush-interval-ms).
 * Dieser Service ist der einzige Ort im gesamten System, der Nachrichten
 * persistiert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageBatchWriter {

    private final MessageRepository messageRepository;

    @Value("${batch.size:50}")
    private int batchSize;

    private final List<MessageEvent> buffer = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public void enqueue(MessageEvent event) {
        List<MessageEvent> toFlush = null;
        lock.lock();
        try {
            buffer.add(event);
            if (buffer.size() >= batchSize) {
                toFlush = new ArrayList<>(buffer);
                buffer.clear();
            }
        } finally {
            lock.unlock();
        }
        if (toFlush != null) {
            persist(toFlush);
        }
    }

    @Scheduled(fixedDelayString = "${batch.flush-interval-ms:5000}")
    public void flushOnSchedule() {
        List<MessageEvent> toFlush = null;
        lock.lock();
        try {
            if (!buffer.isEmpty()) {
                toFlush = new ArrayList<>(buffer);
                buffer.clear();
            }
        } finally {
            lock.unlock();
        }
        if (toFlush != null) {
            persist(toFlush);
        }
    }

    private void persist(List<MessageEvent> events) {
        List<Message> entities = events.stream()
                .map(e -> Message.builder()
                        .chatId(e.getChatId())
                        .userId(e.getUserId())
                        .content(e.getContent())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();
        messageRepository.saveAll(entities);
        log.info("{} Nachricht(en) gebündelt gespeichert", entities.size());
    }
}
