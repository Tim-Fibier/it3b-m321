package ch.benedict.m321.chatservice.stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Nachholen ohne Server und ohne Datenbank: Was eine Verbindung im
 * Modus "holt nach" bekommt, wie sie Nachgeholtes und Puffer zusammenführt und
 * dass das Umschalten auf live nichts verliert und nichts verdoppelt.
 *
 * Hier lässt sich der heikle Moment gezielt herstellen: eine Nachricht, die
 * zugleich in der Datenbank steht und live ankommt. Über echtes HTTP wäre das
 * nur Glückssache.
 */
class StreamConnectionTest {

    /** Ein fester Zeitpunkt, damit die Reihenfolge in den Tests klar ist. */
    private static final Instant T0 = Instant.parse("2026-11-13T08:00:00Z");

    /** Solange nachgeholt wird, geht keine Live-Nachricht raus. */
    @Test
    void buffersLiveMessagesWhileCatchingUp() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        StreamConnection connection = new StreamConnection(emitter, true);

        connection.deliver(event(1));

        assertTrue(emitter.sentIds.isEmpty());
    }

    /**
     * Eine Nachricht, die schon in der Datenbank stand und zugleich live ankam,
     * geht genau einmal raus. Alles kommt nach Zeit sortiert.
     */
    @Test
    void mergesReplayAndBufferWithoutDuplicates() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        StreamConnection connection = new StreamConnection(emitter, true);
        StreamEvent fromDatabase = event(1);
        StreamEvent inBoth = event(2);
        StreamEvent onlyLive = event(3);

        connection.deliver(inBoth);
        connection.deliver(onlyLive);
        connection.catchUp(List.of(fromDatabase, inBoth));

        List<UUID> expected = List.of(fromDatabase.id(), inBoth.id(), onlyLive.id());
        assertEquals(expected, emitter.sentIds);
    }

    /** Nach dem Nachholen geht eine Live-Nachricht sofort raus. */
    @Test
    void deliversImmediatelyAfterCatchUp() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        StreamConnection connection = new StreamConnection(emitter, true);
        connection.catchUp(List.of());

        StreamEvent live = event(1);
        connection.deliver(live);

        assertEquals(List.of(live.id()), emitter.sentIds);
    }

    /**
     * Eine Nachricht stand schon in der Datenbank, ihre Live-Kopie kommt erst
     * nach dem Umschalten, etwa bei Rückstau in der Live-Queue. Sie darf nicht
     * ein zweites Mal rausgehen.
     */
    @Test
    void skipsLateLiveCopyOfCaughtUpMessage() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        StreamConnection connection = new StreamConnection(emitter, true);
        StreamEvent fromDatabase = event(1);
        connection.catchUp(List.of(fromDatabase));

        connection.deliver(fromDatabase);

        assertEquals(List.of(fromDatabase.id()), emitter.sentIds);
    }

    /**
     * Der heikle Moment unter Last: Ein zweiter Thread liefert 1000 Nachrichten,
     * während umgeschaltet wird; die ersten 100 stehen auch in der Datenbank.
     * Am Ende ist jede genau einmal angekommen, in aufsteigender Reihenfolge.
     */
    @Test
    void switchingToLiveLosesAndDuplicatesNothing() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        StreamConnection connection = new StreamConnection(emitter, true);
        List<StreamEvent> all = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            StreamEvent created = event(i);
            all.add(created);
        }
        List<StreamEvent> replay = all.subList(0, 100);

        Thread liveThread = new Thread(() -> deliverAll(connection, all));
        liveThread.start();
        connection.catchUp(replay);
        liveThread.join();

        assertEquals(1000, emitter.sentIds.size());
        Set<UUID> unique = new HashSet<>(emitter.sentIds);
        assertEquals(1000, unique.size());
        for (int i = 0; i < 1000; i++) {
            StreamEvent expected = all.get(i);
            UUID sent = emitter.sentIds.get(i);
            assertEquals(expected.id(), sent, "Reihenfolge verletzt an Stelle " + i);
        }
    }

    /** Liefert alle Ereignisse nacheinander, so wie der Listener-Thread. */
    private void deliverAll(StreamConnection connection, List<StreamEvent> events) {
        for (StreamEvent event : events) {
            try {
                connection.deliver(event);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    /** Ein Ereignis, das umso später liegt, je grösser die Nummer ist. */
    private StreamEvent event(int number) {
        UUID id = UUID.randomUUID();
        Instant sentAt = T0.plusMillis(number);
        return new StreamEvent(id, sentAt, "{\"content\":\"" + number + "\"}");
    }

    /**
     * Ein Emitter, der nichts über das Netz schickt, sondern sich die IDs der
     * gesendeten Ereignisse merkt. Kommentare zählen nicht, nur Nachrichten.
     */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<UUID> sentIds = new ArrayList<>();

        /** Liest aus dem fertigen Ereignis die Zeile "id:" und merkt sich die ID. */
        @Override
        public synchronized void send(SseEventBuilder builder) {
            Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : parts) {
                Object data = part.getData();
                text.append(data);
            }
            String[] lines = text.toString().split("\n");
            for (String line : lines) {
                if (line.startsWith("id:")) {
                    String id = line.substring("id:".length());
                    UUID uuid = UUID.fromString(id);
                    sentIds.add(uuid);
                }
            }
        }
    }
}
