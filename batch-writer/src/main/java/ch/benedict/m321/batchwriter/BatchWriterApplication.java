package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Dieser Dienst ist der einzige, der in die Tabelle message schreibt. Er
 * holt die Nachrichten aus der Queue chat.persist und legt sie gebündelt in
 * PostgreSQL ab. Er hat keinen Webserver und keinen Port: nach aussen gibt
 * es nichts, was man aufrufen könnte.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /** Startet Spring. Alles Weitere erledigen die Beans in den Unterpaketen. */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
