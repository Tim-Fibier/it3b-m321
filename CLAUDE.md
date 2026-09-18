# M321 — Lernprojekt IT3b

Unterrichtsprojekt der Klasse IT3b. Der Code wird von Lernenden gelesen, verstanden und **mündlich erklärt**.
Massstab ist nicht Eleganz, sondern: kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?

## Codestil

- **Eine Anweisung pro Zeile.** Keine verschachtelten One-Liner, keine Ketten aus Stream/Optional/
  Ternary. Zwischenresultate in benannte Variablen legen, auch wenn sie nur einmal gebraucht werden.
- **Keine Sondermodelle.** Kein Reflection, keine generischen Basisklassen, keine Annotation-Magie,
  keine Design Patterns, die im Unterricht nicht behandelt wurden. Wenn eine Lösung eine Erklärung
  braucht, die über den Stoff hinausgeht, ist es die falsche Lösung.
- **Standardbibliothek und einfache Konstrukte.** `for`-Schleife statt Stream-Pipeline, `if` statt
  Ternary-Verschachtelung, `ArrayList`/`HashMap` statt exotischer Collections.
- **Sprechende Namen** in ganzen Wörtern (`empfangeneNachricht`, nicht `msg2`).
- **Kurze Methoden** mit genau einer Aufgabe. Lieber drei benannte Methoden als eine kluge.

## Kommentare

- Über jeder Methode ein bis zwei Sätze: **was** sie tut und **warum** es sie gibt.
- Innerhalb einer Methode jeder nicht offensichtliche Schritt kommentiert — besonders alles, was mit
  Netzwerk, Threads, Sockets oder Nebenläufigkeit zu tun hat.
- Kommentare auf Deutsch, im Ton einer Erklärung an eine Mitlernende, nicht als Stichwort.
- Kein Kommentar, der nur den Code wiederholt (`// i erhöhen`). Kommentiert wird die Absicht.

## Übungsaufgaben

Vereinzelt — nicht bei jedem Auftrag — eine **kleine Codingaufgabe** an die Lernenden vergeben statt sie
selbst zu lösen. Regeln dafür:

- Umfang: **2–3 Zeilen** einfacher Code an einer klar bezeichneten Stelle.
- Die Stelle im Code mit `// TODO Übung: <Aufgabe in einem Satz>` markieren.
- In der Antwort kurz sagen, was dort passieren soll und welchen Baustein es dafür braucht —
  **ohne die Lösung hinzuschreiben**.
- Der Rest der Datei muss ohne diese Zeilen kompilieren oder mit einem klaren Hinweis fehlschlagen,
  nie stillschweigend falsch laufen.
- Wird die Lösung eingereicht: durchgehen, benennen was gut ist, höchstens einen Punkt verbessern.

## Antworten

Erklären statt abliefern. Zu jeder Änderung in wenigen Sätzen: was wurde gemacht, warum so, und
welcher Teil davon Prüfungsstoff ist.

---

# Ergänzende Detailregeln

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
