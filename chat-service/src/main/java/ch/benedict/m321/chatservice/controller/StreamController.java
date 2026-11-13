package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.stream.StreamRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * Der Live-Weg nach aussen: Ein Client öffnet einen Stream für einen Raum und
 * bekommt dessen Nachrichten als Server-Sent Events, solange er verbunden bleibt.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class StreamController {

    /**
     * 0 heisst: kein Zeitlimit. Ein Stream soll offen bleiben, solange der Client
     * zuhört, und nicht nach dem Standard-Zeitlimit des Servers abbrechen.
     */
    private static final long NO_TIMEOUT = 0L;

    /** Text des ersten SSE-Kommentars. Kommentare zeigt ein Client nicht als Nachricht an. */
    private static final String CONNECTED_COMMENT = "verbunden";

    private final StreamRegistry streamRegistry;

    /**
     * Öffnet einen Stream für einen Raum.
     *
     * Fehlt roomId oder ist sie keine UUID, lehnt Spring den Aufruf mit 400 ab,
     * bevor diese Methode läuft. Der Kommentar geht sofort raus, damit der Client
     * die Antwort-Header bekommt, ohne auf die erste Nachricht zu warten.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("roomId") UUID roomId) throws IOException {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        SseEmitter.SseEventBuilder connected = SseEmitter.event().comment(CONNECTED_COMMENT);
        emitter.send(connected);

        streamRegistry.register(roomId, emitter);
        log.info("Stream opened for room {}", roomId);
        return emitter;
    }
}
