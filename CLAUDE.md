# Codestil — M321 Chat-App

Massstab: Kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?

## Java / Spring Boot

### Allgemein
- Eine Anweisung pro Zeile. Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, wenn der Zweck nicht sofort klar ist.
- `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern: `calculateTotalPrice` statt `calcTot`, `activeUsers` statt `actU`.

### Über jeder Methode und Klasse
- Ein bis zwei Sätze: Was macht sie? Warum gibt es sie?
- Beispiel:
  ```java
  /**
   * Validiert ein JWT-Token gegen die JWKS des Keycloak-Servers.
   * Wird vor jeder WebSocket-Nachricht aufgerufen.
   */
  public boolean isTokenValid(String token) {
    // ...
  }
  ```

### Kommentare
- Auf Deutsch, als Erklärung an eine Mitlernende.
- Nur wenn das «Warum» nicht aus dem Code selbst folgt.
- Schlecht: `i++` // Zähler erhöhen
- Gut: `// Nur Chat-Mitglieder auswählen, nicht Zuschauer`

### Exceptions und Fehlerbehandlung
- Eigene Exception-Klassen für Businesslogik (z. B. `TokenValidationException`).
- Logging immer mit `org.slf4j.Logger`, nie `System.out.println`.

### Spring-spezifisch
- `@Service`, `@Repository`, `@RestController` - klare Rollen.
- Dependency Injection über Konstruktor, nicht über `@Autowired` auf Feldern.
- `@Transactional` nur wo nötig, nicht auf jeder Methode.

## JavaScript / Frontend

### Allgemein
- Modul-Pattern oder ES6 `import/export`.
- Klare Funktionsnamen: `setupWebSocketConnection()` statt `setup()`.
- Kommentare auf Deutsch, nur wenn nötig.

### WebSocket
- Callbacks explizit benennen: `onMessageReceived`, `onConnectionClosed`.
- Fehlerbehandlung für jeden Zustand (connect, send, close).

## HTML / CSS

- Aussagekräftige `id` und `class` Namen: `#message-container`, `.chat-input-field`.
- CSS nach Komponenten organisieren.
- Immer `<!DOCTYPE html>` und Meta-Tags (charset, viewport).

## Commit-Meldungen

Deutsch, Präsens, imperativ:
- `Implementiere Chat Service Basis mit Spring Boot`
- `Füge WebSocket-Endpoint hinzu`
- `Repariere Token-Validierung gegen Keycloak`

Nicht: `fixed bugs`, `changes`, `update stuff`.

## Tests

- Jede öffentliche Methode mit mindestens einen Test.
- Test-Namen sagen, was getestet wird: `testTokenValidationRejectsMalformedToken()`.
- Aussagekräftige Asserts mit Fehlermeldungen: 
  ```java
  assertTrue(isValid, "Token sollte gültig sein");
  ```

## Dokumentation

- README auf Deutsch.
- Architektur-Diagramme im `docs/` Ordner.
- Jeder Service mit eigenem `README.md` im Service-Verzeichnis.

---

**Ziel:** Code, den lernende Personen lesen, verstehen und selbst schreiben können.
