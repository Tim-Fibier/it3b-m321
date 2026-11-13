package ch.benedict.m321.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Startpunkt des chat-service.
 *
 * Dieser Dienst ist die einzige Stelle im System, die eine Nachricht vom
 * Benutzer entgegennimmt. Er speichert nichts selbst — er reicht die
 * Nachricht an RabbitMQ weiter.
 *
 * @EnableScheduling schaltet regelmässige Aufgaben ein; gebraucht für den
 * Herzschlag an die offenen Streams.
 */
@SpringBootApplication
@EnableScheduling
public class ChatServiceApplication {

    /** Startet Spring Boot; von hier aus legt Spring Queues, Exchange und Web-Endpunkte an. */
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
