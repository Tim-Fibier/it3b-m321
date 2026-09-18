package ch.bzz.m321.batchservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Haupteinstieg für den Batch Service.
 * Abonniert alle Nachrichten von Redis und speichert sie gebündelt in PostgreSQL.
 * Dies ist der einzige Service, der Nachrichten in die Datenbank schreibt.
 */
@SpringBootApplication
@EnableScheduling
public class BatchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchServiceApplication.class, args);
    }

}
