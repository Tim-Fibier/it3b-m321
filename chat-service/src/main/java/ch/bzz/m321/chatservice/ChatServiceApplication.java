package ch.bzz.m321.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Haupteinstieg für den Chat Service.
 * Stellt REST-API und WebSocket-Endpoints für Live-Chat-Nachrichten bereit.
 */
@SpringBootApplication
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }

}
