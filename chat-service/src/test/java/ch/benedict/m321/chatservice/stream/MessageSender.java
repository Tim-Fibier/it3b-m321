package ch.benedict.m321.chatservice.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Schickt in Tests eine Nachricht über POST /messages, so wie es ein echter
 * Aufrufer tut. Damit läuft der ganze Weg: HTTP, beide Veröffentlichungen,
 * Live-Queue, Stream.
 */
public final class MessageSender {

    /** Ein Client für alle Tests. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /** Liest nur die ID aus der Antwort, dafür reicht ein einfacher Mapper. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Reine Hilfsklasse, wird nie erzeugt. */
    private MessageSender() {
    }

    /**
     * Sendet eine Nachricht an die Instanz auf diesem Port und gibt die ID zurück,
     * die der Dienst vergeben hat. Wirft, wenn der Dienst nicht mit 202 antwortet.
     */
    public static String send(int port, UUID roomId, String senderId, String content)
            throws IOException, InterruptedException {
        String body = "{\"roomId\":\"" + roomId + "\",\"senderId\":\"" + senderId
                + "\",\"senderName\":\"" + senderId + "\",\"content\":\"" + content + "\"}";
        URI uri = URI.create("http://localhost:" + port + "/messages");
        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(bodyPublisher)
                .build();

        HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
        HttpResponse<String> response = HTTP_CLIENT.send(request, bodyHandler);
        if (response.statusCode() != 202) {
            throw new IllegalStateException("POST /messages answered " + response.statusCode());
        }

        String responseBody = response.body();
        JsonNode accepted = OBJECT_MAPPER.readTree(responseBody);
        JsonNode id = accepted.get("id");
        return id.asText();
    }
}
