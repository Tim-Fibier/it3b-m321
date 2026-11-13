package ch.benedict.m321.chatservice.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Eine offene Stream-Verbindung mit zwei Zuständen: "holt nach" oder "live".
 *
 * Verbindet sich ein Client mit Last-Event-ID neu, muss er zuerst die verpassten
 * Nachrichten aus der Datenbank bekommen. Was in dieser Zeit live ankommt, darf
 * weder verloren gehen noch vor dem Nachgeholten erscheinen. Darum wird es
 * gepuffert, bis das Nachholen fertig ist.
 *
 * Alle Methoden sperren die Verbindung. So passiert das Umschalten auf live in
 * einem Stück: Eine Nachricht, die genau dann ankommt, wartet kurz und geht
 * danach raus, nie dazwischen.
 */
public final class StreamConnection {

    private final SseEmitter emitter;

    /** Die gepufferten Live-Nachrichten, solange nachgeholt wird; null, sobald live. */
    private List<StreamEvent> pending;

    /**
     * Die IDs, die beim Nachholen schon gesendet wurden. Eine Nachricht kann in
     * der Datenbank stehen, bevor ihre Live-Kopie ankommt, etwa bei Rückstau in
     * der Live-Queue. Kommt die Kopie erst nach dem Umschalten, wird sie hier
     * erkannt und nicht ein zweites Mal gesendet.
     */
    private Set<UUID> caughtUpIds = Set.of();

    /** Erzeugt eine Verbindung, die sofort live ist oder zuerst nachholt. */
    StreamConnection(SseEmitter emitter, boolean catchingUp) {
        this.emitter = emitter;
        if (catchingUp) {
            this.pending = new ArrayList<>();
        }
    }

    /** Der Emitter dieser Verbindung, damit die Registry sie schliessen kann. */
    SseEmitter emitter() {
        return emitter;
    }

    /**
     * Eine Live-Nachricht: solange nachgeholt wird, puffern; danach senden, ausser
     * sie ging beim Nachholen schon raus.
     */
    synchronized void deliver(StreamEvent event) throws IOException {
        if (pending != null) {
            pending.add(event);
            return;
        }
        UUID id = event.id();
        if (caughtUpIds.contains(id)) {
            return;
        }
        send(event);
    }

    /** Ein SSE-Kommentar, etwa der Herzschlag. Kommentare sind keine Nachrichten und müssen nicht warten. */
    synchronized void sendComment(String text) throws IOException {
        SseEmitter.SseEventBuilder comment = SseEmitter.event().comment(text);
        emitter.send(comment);
    }

    /**
     * Schliesst das Nachholen ab und schaltet auf live.
     *
     * Nachgeholtes und Puffer werden über die ID zusammengeführt: Eine Nachricht,
     * die schon in der Datenbank stand und zugleich live ankam, geht nur einmal
     * raus. Danach wird nach (sentAt, id) sortiert, wie in der Datenbank.
     */
    synchronized void catchUp(List<StreamEvent> replay) throws IOException {
        Map<UUID, StreamEvent> byId = new LinkedHashMap<>();
        for (StreamEvent event : replay) {
            byId.put(event.id(), event);
        }
        for (StreamEvent event : pending) {
            byId.putIfAbsent(event.id(), event);
        }
        pending = null;
        Set<UUID> sentIds = byId.keySet();
        caughtUpIds = new HashSet<>(sentIds);

        List<StreamEvent> merged = new ArrayList<>(byId.values());
        merged.sort(StreamEvent::compareOrder);
        for (StreamEvent event : merged) {
            send(event);
        }
    }

    /** Schreibt ein Ereignis: "id:" ist die Nachrichten-ID, "data:" das JSON. */
    private void send(StreamEvent event) throws IOException {
        UUID id = event.id();
        String messageId = id.toString();
        String json = event.json();
        SseEmitter.SseEventBuilder sseEvent = SseEmitter.event()
                .id(messageId)
                .data(json);
        emitter.send(sseEvent);
    }
}
