package ch.benedict.m321.chatservice.stream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ein kleiner SSE-Client für Tests, über eine echte HTTP-Verbindung.
 *
 * Ein Mock des Emitters würde nicht zeigen, ob die Bytes auf der Leitung wirklich
 * dem SSE-Format entsprechen. Dieser Client liest sie so, wie es ein Browser tut.
 */
public final class SseTestClient implements AutoCloseable {

    /** Ein Client für alle Tests. HTTP/1.1, weil der Server Streams so ausliefert. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final HttpResponse<InputStream> response;
    private final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> comments = new LinkedBlockingQueue<>();
    private final Thread reader;

    /** Startet das Lesen im Hintergrund, damit der Test nicht blockiert. */
    private SseTestClient(HttpResponse<InputStream> response) {
        this.response = response;
        this.reader = new Thread(this::readUntilClosed, "sse-test-client");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /**
     * Öffnet einen Stream. Kehrt zurück, sobald die Antwort-Header da sind; danach
     * ist die Verbindung auf dem Server bereits registriert.
     */
    public static SseTestClient connect(URI uri) throws IOException, InterruptedException {
        return connect(uri, null);
    }

    /**
     * Öffnet einen Stream als Neuverbindung: mit Last-Event-ID, so wie es ein
     * Browser nach einem Abbruch von selbst tut. null heisst ohne Header.
     */
    public static SseTestClient connect(URI uri, String lastEventId) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(uri, lastEventId);
        HttpResponse.BodyHandler<InputStream> bodyHandler = HttpResponse.BodyHandlers.ofInputStream();
        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, bodyHandler);
        return new SseTestClient(response);
    }

    /** Für Aufrufe, die abgewiesen werden sollen: Nur der Statuscode zählt. */
    public static int statusOf(URI uri) throws IOException, InterruptedException {
        return statusOf(uri, null);
    }

    /** Wie statusOf, aber mit Last-Event-ID; null heisst ohne Header. */
    public static int statusOf(URI uri, String lastEventId) throws IOException, InterruptedException {
        HttpRequest request = buildRequest(uri, lastEventId);
        HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
        HttpResponse<String> response = HTTP_CLIENT.send(request, bodyHandler);
        return response.statusCode();
    }

    /** Baut die GET-Anfrage für einen Stream, mit oder ohne Last-Event-ID. */
    private static HttpRequest buildRequest(URI uri, String lastEventId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .GET();
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        return builder.build();
    }

    /** Der HTTP-Status der Antwort. */
    public int statusCode() {
        return response.statusCode();
    }

    /** Der Content-Type der Antwort, leer, wenn der Server keinen gesetzt hat. */
    public String contentType() {
        Optional<String> header = response.headers().firstValue("Content-Type");
        return header.orElse("");
    }

    /** Wartet höchstens timeout auf das nächste Ereignis; null, wenn keines kommt. */
    public SseEvent nextEvent(Duration timeout) throws InterruptedException {
        long millis = timeout.toMillis();
        return events.poll(millis, TimeUnit.MILLISECONDS);
    }

    /** Wartet höchstens timeout auf den nächsten SSE-Kommentar; null, wenn keiner kommt. */
    public String nextComment(Duration timeout) throws InterruptedException {
        long millis = timeout.toMillis();
        return comments.poll(millis, TimeUnit.MILLISECONDS);
    }

    /** Trennt die Verbindung, so wie ein Client, der die Seite schliesst. */
    @Override
    public void close() throws IOException {
        InputStream body = response.body();
        body.close();
        reader.interrupt();
    }

    /**
     * Liest Zeile für Zeile nach dem SSE-Format: "id:" und "data:" sammeln, eine
     * Leerzeile schliesst das Ereignis ab, eine Zeile mit ":" ist ein Kommentar.
     */
    private void readUntilClosed() {
        InputStream body = response.body();
        InputStreamReader streamReader = new InputStreamReader(body, StandardCharsets.UTF_8);
        try (BufferedReader lines = new BufferedReader(streamReader)) {
            String id = null;
            String data = null;
            String line = lines.readLine();
            while (line != null) {
                if (line.isEmpty()) {
                    if (data != null) {
                        SseEvent event = new SseEvent(id, data);
                        events.add(event);
                    }
                    id = null;
                    data = null;
                } else if (line.startsWith(":")) {
                    String comment = valueAfter(line, ":");
                    comments.add(comment);
                } else if (line.startsWith("id:")) {
                    id = valueAfter(line, "id:");
                } else if (line.startsWith("data:")) {
                    data = valueAfter(line, "data:");
                }
                line = lines.readLine();
            }
        } catch (IOException exception) {
            // Die Verbindung wurde getrennt, absichtlich oder vom Server. Für den Test
            // zählt nur, was bis dahin angekommen ist.
        }
    }

    /** Der Wert hinter dem Feldnamen. Ein Leerzeichen direkt danach gehört nicht dazu. */
    private static String valueAfter(String line, String field) {
        String value = line.substring(field.length());
        if (value.startsWith(" ")) {
            return value.substring(1);
        }
        return value;
    }
}
