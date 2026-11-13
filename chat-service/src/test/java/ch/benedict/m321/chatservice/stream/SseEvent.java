package ch.benedict.m321.chatservice.stream;

/**
 * Ein empfangenes SSE-Ereignis, so wie der Client es sieht: der Wert aus "id:"
 * und der Text aus "data:".
 */
public record SseEvent(String id, String data) {
}
