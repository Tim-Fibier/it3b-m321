package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.stream.StreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
public class StreamController {

    private final StreamService streamService;

    /**
     * Öffnet einen Stream für einen Raum.
     *
     * Fehlt roomId oder ist sie keine UUID, lehnt Spring den Aufruf mit 400 ab,
     * bevor diese Methode läuft. Last-Event-ID schickt ein Browser von selbst mit,
     * wenn er sich nach einem Abbruch neu verbindet: die ID der letzten Nachricht,
     * die er bekommen hat.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("roomId") UUID roomId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId)
            throws IOException {
        return streamService.open(roomId, lastEventId);
    }
}
