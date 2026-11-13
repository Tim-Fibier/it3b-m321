# Live-Zustellung — Spezifikation

**Modul M321 · Klasse IT3c · Stand 13.11.2026**

Grundlage: der aktuelle Code auf `main`, insbesondere `chat-service` und `docker-compose.yml`, sowie [`PLANUNG.md`](../PLANUNG.md). Diese Spezifikation unterscheidet ausdrücklich zwischen dem, was im Repository bereits läuft, und dem, was erst geplant ist. **Eine Live-Zustellung bis in einen Browser oder ein SSE-Stream ist im aktuellen Stand noch nicht implementiert.**

---

## 1. Zweck und Abgrenzung

Der vorhandene `chat-service` nimmt Nachrichten über `POST /messages` entgegen und veröffentlicht sie in zwei unabhängige Wege: auf die persistente Queue `chat.persist` und an den Fanout-Exchange `chat.delivery`. Der Exchange ist als Einstieg für die spätere Live-Zustellung vorgesehen; eine Gateway-Instanz, eine Live-Queue und ein SSE-Endpunkt gibt es im aktuellen Repository aber noch nicht.

Nicht Teil des aktuellen Stands sind Keycloak, `web-gateway`, React, WebSocket/SSE, Räume und deren Mitgliedschaften sowie die Auswahl der Empfänger. Diese Punkte sind in `PLANUNG.md` beschrieben, aber noch nicht als Dienste oder Funktionen im Repository vorhanden.

---

## 2. Vertrag

### 2.1 Was am Exchange ankommt

Der `chat-service` veröffentlicht die Nachricht mit `MessagePublisher.publish()` zuerst auf `chat.persist` und danach auf dem Exchange `chat.delivery`. Für den Exchange verwendet er den leeren Routing-Key; bei einem Fanout-Exchange wird der Routing-Key ignoriert. Quelle: `chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessagePublisher.java`.

| Eigenschaft | Aktueller Wert | Beleg |
|---|---|---|
| Exchange | `chat.delivery` | `QueueNames.DELIVERY_EXCHANGE` |
| Typ | Fanout, dauerhaft (`durable = true`), nicht automatisch löschbar | `RabbitConfig.deliveryExchange()` |
| Erzeuger | `chat-service` | `MessagePublisher.publish()` |
| Routing-Key | leer (`""`); beim Fanout ohne Bedeutung | `MessagePublisher.FANOUT_ROUTING_KEY` |
| Format | JSON, erzeugt durch `Jackson2JsonMessageConverter` | `RabbitConfig.jsonMessageConverter()` |
| Nutzdaten | `id`, `roomId`, `senderId`, `senderName`, `content`, `sentAt` | `dto/ChatMessage.java` |

Die Nachricht ist ein `ChatMessage`-Record. `id` und `sentAt` vergibt der `chat-service`; `roomId`, Absender und Text kommen aus dem Request. `sentAt` wird als ISO-8601-Zeitpunkt aus einem Java-`Instant` serialisiert. Der genaue JSON-Aufbau entspricht den Record-Feldern und dem Jackson-Konverter; im Code gibt es kein eigenes SSE-Ereignisformat.

Wichtig: Ein Fanout-Exchange speichert Nachrichten nicht selbst. Er verteilt eine veröffentlichte Nachricht an die Queues, die zu diesem Zeitpunkt daran gebunden sind. Gibt es keine gebundene Queue, gibt es im aktuellen Setup keinen dauerhaften Empfänger für diesen Zustellweg. Der Exchange allein ist kein Nachrichtenpuffer.

### 2.2 Was am Stream hinausgeht

Im Repository gibt es **keinen Stream-Endpunkt**. Es gibt weder SSE (`text/event-stream`) noch einen WebSocket-Endpunkt, der `chat.delivery` konsumiert. Entsprechend sind aktuell nicht festgelegt oder implementiert:

- Endpunkt, Parameter und Authentisierung eines Streams;
- SSE-Felder wie `id:` und `data:` oder Kommentar-Frames;
- Heartbeats, Verbindungs-Abbruch und Wiederverbindung;
- `Last-Event-ID` und das Nachholen verpasster Nachrichten.

Die Testklasse `MessagePublisherIntegrationTest` bildet einen späteren Empfänger nur teilweise nach: Sie legt eine an `chat.delivery` gebundene anonyme Queue an und prüft, dass die veröffentlichte Nachricht dort ankommt. Das ist ein Test des Broker-Vertrags, kein vorhandener Gateway- oder SSE-Endpunkt.

**Raum-ID:** `POST /messages` verlangt eine nicht leere UUID als `roomId` (`@NotNull UUID`). Eine fehlende oder syntaktisch ungültige Raum-ID wird beim Request abgewiesen. Ob eine syntaktisch gültige UUID tatsächlich zu einem Raum gehört, wird nicht geprüft: Es gibt noch keine Raumverwaltung und keine Mitgliedschaftsprüfung. Daher kann der aktuelle Live-Zustellweg nicht gezielt nur an Mitglieder eines Raums zustellen; die spätere Empfänger-Auswahl ist nicht umgesetzt.

---

## 3. Queues und Exchange

| Baustein | Name | Lebensdauer | Grenze | Was an der Grenze passiert | Begründung |
|---|---|---|---|---|---|
| Geplante Live-Queue pro Gateway-Instanz | dynamischer Name einer anonymen Queue; noch nicht im Produktivcode angelegt | Laut Planung exklusiv und automatisch löschbar; in der aktuellen Anwendung nicht vorhanden | Noch nicht implementiert | Kein Gateway-Consumer bedeutet derzeit: keine Live-Queue und kein Abholen der Live-Nachricht | Jede Gateway-Instanz muss später jede Nachricht sehen, weil ihre eigenen Clients an genau dieser Instanz hängen |
| Live-Exchange | `chat.delivery` | dauerhaft; wird beim Start von `chat-service` deklariert | Keine eigene Speicherkapazität oder Aufbewahrung; der Exchange puffert nicht | Verteilung an momentan gebundene Queues; ohne Bindung wird nichts für spätere Empfänger aufgehoben | Fanout lässt jede Gateway-Instanz die Nachricht erhalten |
| Schreib-Queue | `chat.persist` | durable classic Queue; Nachrichten werden vom `batch-writer` verarbeitet | Paketgrösse im batch-writer: bis 500 Nachrichten oder 200 ms; keine projektspezifische Queue-Längen-Grenze konfiguriert | Wartende Nachrichten bleiben in der Queue, bis der batch-writer sie verarbeitet; endgültig abgelehnte Nachrichten werden per DLX an `chat.dlq` geleitet | Puffer für den Datenbank-Schreibweg; kein Live-Zustellweg |
| Dead-Letter-Queue | `chat.dlq` | durable classic Queue | Keine projektseitig konfigurierte Kapazitätsgrenze | Derzeit kein eigener Auswerter oder Wiedereinspiel-Dienst im Repository | Hält vom batch-writer endgültig abgelehnte Schreibnachrichten fest; hat nichts mit fehlgeschlagener Live-Zustellung zu tun |

Die erste Tabellenzeile beschreibt das in `PLANUNG.md` vorgesehene Muster, nicht den aktuellen Laufzeitstand. Die aktuelle `RabbitConfig` legt den Exchange und die Queues `chat.persist` sowie `chat.dlq` an; sie legt **keine** anonyme Live-Queue an.

---

## 4. Verhalten

### 4.1 Normalfall: der Weg einer Nachricht von POST bis zur Veröffentlichung

1. Ein Aufrufer sendet einen gültigen Request an `POST /messages` im `chat-service`.
2. Bean Validation prüft, dass `roomId` vorhanden ist und `senderId`, `senderName` sowie `content` nicht leer sind.
3. `MessageService` vergibt eine UUID und einen Server-Zeitstempel und baut daraus ein `ChatMessage`.
4. `MessagePublisher` veröffentlicht das JSON zuerst nach `chat.persist`, dann an `chat.delivery` mit leerem Routing-Key.
5. Bei erfolgreicher Verarbeitung antwortet der Controller mit `202 Accepted` und gibt `id` sowie `sentAt` zurück.
6. `batch-writer` verarbeitet unabhängig davon `chat.persist`. Für `chat.delivery` gibt es im aktuellen Stack keinen Gateway-Consumer und keinen Stream, der die Nachricht einem Client anzeigt.

Die Antwort `202` bedeutet angenommen und veröffentlicht, nicht: in der Datenbank gespeichert oder einem Browser zugestellt. Die beiden RabbitMQ-Veröffentlichungen erfolgen nacheinander und sind keine gemeinsame Transaktion. Der Code aktiviert keine Publisher Confirms. Fällt das zweite Senden aus, kann das erste bereits erfolgreich in `chat.persist` liegen, obwohl der Aufrufer einen Fehler erhält.

### 4.2 Zwei Instanzen (S3)

Für den späteren Live-Pfad sieht die Planung eine eigene, exklusive Queue pro Gateway-Instanz vor. Beide Queues werden an denselben Fanout-Exchange gebunden; dadurch erhält jede Instanz eine Kopie derselben veröffentlichten Nachricht. Das ist nötig, weil jede Instanz nur ihre eigenen WebSocket-Verbindungen kennt.

**Aktueller Stand:** Es gibt weder Gateway-Instanzen noch die Live-Queues oder deren Bindings. Der Test `publishesToDeliveryExchange()` prüft eine einzelne temporäre Test-Queue, nicht zwei Gateways. Zwei-Instanz-Verhalten ist daher geplant, aber nicht mit dem Projektstack abgenommen.

### 4.3 Zurück auf eine Instanz (S4)

Bei zwei laufenden Gateway-Instanzen soll nach dem Herunterskalieren auf eine Instanz deren eigene Queue-Bindung weiter Nachrichten erhalten. Die entfernte Instanz und ihre exklusive Queue sollen beim Beenden verschwinden. Das Verhalten hängt von einer noch zu implementierenden Queue-Verwaltung im Gateway ab.

**Aktueller Stand:** Nicht implementiert und nicht getestet. Der Austausch der `chat-service`-Instanzzahl ist kein Ersatz für das Gateway-Skalierungsszenario.

### 4.4 Datenbank und batch-writer weg (S5)

Der Live-Exchange und der persistente Schreibweg sind getrennte Veröffentlichungen. Der `batch-writer`-Ausfall wird durch die dauerhafte Queue `chat.persist` abgefedert: Nachrichten warten dort, bis ein Verbraucher zurückkehrt. Das betrifft die Persistenz und bedeutet nicht, dass die Live-Zustellung nachträglich wiederholt wird.

Fällt Postgres aus, behandelt der batch-writer den Schreibfehler gemäss `docs/spec-batch-writer.md` durch Requeue und Wiederholung. Live-Nachrichten werden nicht aus der Datenbank nachgeladen und es gibt keinen SSE-Replay-Mechanismus.

**Aktueller Stand:** Das Requeue-Verhalten ist im batch-writer spezifiziert und durch dessen Integrationstests abgedeckt. Eine Wiederherstellung verpasster Live-Nachrichten ist nicht vorhanden.

### 4.5 Hängende Instanz (S6)

Für den zukünftigen Gateway-Consumer müssen Verbindungsabbruch und Broker-Wiederverbindung festgelegt und getestet werden. Im Live-Pfad kann eine Instanz Nachrichten, die während ihrer Abwesenheit veröffentlicht wurden, nur nachträglich erhalten, wenn ihre Queue diese Zeit überlebt und Nachrichten darin warteten. Eine exklusive, automatisch gelöschte Queue verschwindet beim Ende ihrer Verbindung; sie ist deshalb kein dauerhafter Verlaufsspeicher.

**Aktueller Stand:** Kein Gateway-Consumer und keine Instanz-Queue, daher gibt es dazu noch kein implementiertes oder gemessenes Verhalten. Der langlebige Nachrichtenpuffer ist allein `chat.persist` für den batch-writer.

### 4.6 Neuverbindung mit Last-Event-ID (S7)

`Last-Event-ID` ist ein SSE-Mechanismus und im Repository nicht implementiert. Nachrichten-IDs sind zwar UUIDs, aber der Server speichert keine Zuordnung von SSE-Ereignissen zu wiederaufnehmbaren Stream-Positionen. Es gibt auch keinen SSE-Verlauf-Endpunkt, der Nachrichten nach einer ID aus Postgres nachliefern könnte.

**Ergebnis:** Eine Client-Neuverbindung kann aktuell keine verpassten Live-Nachrichten nachholen. Dafür braucht es zuerst einen Stream-Endpunkt, einen gespeicherten oder anderweitig wiederauffindbaren Ereignisverlauf und eine definierte Reihenfolge samt Wiederaufnahmevertrag.

---

## 5. Abnahmekriterien

Die folgenden Kriterien trennen bestehende Prüfungen von noch offenen Funktionen. Die Befehle sind dem Repository entnommen; sie wurden beim Erstellen dieser Dokumentation nicht erneut ausgeführt.

| Nr | Kriterium | Befehl | Erwartet |
|---|---|---|---|
| S1 | Chat-Service nimmt einen gültigen Request an und legt ihn auf `chat.persist` | `mvn -pl chat-service -Dtest=MessageControllerIntegrationTest test` | Integrationstest prüft `202 Accepted`, `id` und `sentAt` sowie eine Nachricht in `chat.persist` |
| S2 | Nachricht wird an `chat.delivery` veröffentlicht und erreicht eine gebundene Queue | `mvn -pl chat-service -Dtest=MessagePublisherIntegrationTest test` | Integrationstest liest Nachricht aus einer gebundenen anonymen Test-Queue; das beweist weder SSE noch Browser-Zustellung |
| S3 | Zwei Gateway-Instanzen erhalten dieselbe Nachricht über je eine Live-Queue | Noch kein gültiger Stack-Befehl vorhanden | **Offen:** Gateway, zwei Live-Queues und Test fehlen |
| S4 | Nach Rückkehr auf eine Gateway-Instanz erhält diese weiter Nachrichten | Noch kein gültiger Stack-Befehl vorhanden | **Offen:** Skalierungs- und Bereinigungstest fehlt |
| S5 | Datenbank-/batch-writer-Ausfall verliert keine Persistenznachrichten | `mvn -pl batch-writer -Dtest=DatabaseOutageIntegrationTest test` | Der batch-writer-Test prüft Wiederholung und spätere Speicherung; er prüft keine Live-Wiederzustellung |
| S6 | Ausfall oder hängende Gateway-Instanz hat definiertes Live-Verhalten | Noch kein gültiger Stack-Befehl vorhanden | **Offen:** Gateway-Consumer und Ausfalltest fehlen |
| S7 | SSE-Neuverbindung kann mit `Last-Event-ID` fortsetzen | Noch kein gültiger Stack-Befehl vorhanden | **Offen:** kein SSE-Endpunkt und kein Replay vorhanden |
| S8 | Gültige, aber nicht vorhandene Raum-ID wird nicht fälschlich als existierender Raum behandelt | Noch kein gültiger Stack-Befehl vorhanden | **Offen:** Raumtabelle und Existenzprüfung fehlen; aktuell wird eine syntaktisch gültige UUID nicht auf Existenz geprüft |

Zusätzlicher vorhandener Test: `RabbitConfigIntegrationTest` prüft, dass `chat.persist` und `chat.dlq` beim Start deklariert werden. Er prüft keine Live-Queue. Der vollständige vorhandene Testlauf ist `mvn clean test`.

---

## 6. Entscheide und offene Punkte

| Entscheid | Begründung | Verworfen |
|---|---|---|
| `chat.delivery` ist ein Fanout-Exchange | Jede später gebundene Gateway-Queue soll eine Nachricht erhalten; der Fanout ignoriert den Routing-Key | Eine einzelne gemeinsame Gateway-Queue: Competing Consumers würden die Nachricht nur an eine Gateway-Instanz liefern, obwohl Clients auf mehreren Instanzen verteilt sind |
| `chat.persist` und `chat.delivery` sind getrennte Wege | Speicherung soll den Live-Pfad nicht auf die Datenbank warten lassen | Live-Zustellung erst nach dem Datenbank-Commit |
| Die erfolgreiche HTTP-Antwort ist `202 Accepted` | Sie bedeutet angenommen/veröffentlicht, nicht dauerhaft gespeichert | `201 Created` als Behauptung, die Nachricht sei bereits gespeichert |
| Ein Gateway mit eigener exklusiver Queue ist Planungsstand, noch keine Implementierung | Jede Gateway-Instanz muss später ihre eigenen verbundenen Clients bedienen; temporäre Queue passt zu einer Instanz-Verbindung | Eine nicht vorhandene Gateway-Funktion als bereits umgesetzt dokumentieren |
| Es wird aktuell keine SSE-Semantik behauptet | Kein Stream-Endpunkt, kein `Last-Event-ID`, kein Replay-Code vorhanden | Einen `id:`/`data:`-Vertrag oder Wiederverbindungsverhalten erfinden |
| Raumexistenz und Mitgliedschaften sind offen | Der aktuelle Request validiert nur UUID-Form und Pflichtfelder; es gibt keine Raumverwaltung | So tun, als ob `roomId` bereits Autorisierung oder Empfängerfilterung bewirkt |

### Offene Punkte

| Nr | Punkt | Nächster nötiger Schritt |
|---|---|---|
| O1 | Gateway konsumiert `chat.delivery` noch nicht | Gateway-Dienst und dessen RabbitMQ-Verbindung implementieren |
| O2 | Live-Queue pro Gateway-Instanz fehlt | Lebensdauer, Exklusivität, automatische Löschung und Verhalten bei Neustart festlegen und testen |
| O3 | Kein WebSocket- oder SSE-Endpunkt | Protokoll und Endpunkt festlegen; nicht beide gleichzeitig voraussetzen |
| O4 | Kein `Last-Event-ID`-Replay | Zuerst klären, woher der wiederaufnehmbare Nachrichtenverlauf kommt und wie sortiert wird |
| O5 | `roomId` wird nicht gegen existierende Räume geprüft | Raum- und Mitgliedschaftsmodell ergänzen, bevor gezielte Zustellung behauptet wird |
| O6 | Veröffentlichung in zwei Wege ist nicht atomar; Publisher Confirms sind nicht aktiviert | Fehlerfenster benennen und bei Bedarf gezielt härten; die aktuelle Planung führt Publisher Confirms als offenen Punkt |
| O7 | Keine gebundene Live-Queue bedeutet keine spätere Zustellung | Im Gateway-Lebenszyklus sicherstellen, dass eine Queue rechtzeitig gebunden ist; für Offline-Clients braucht es einen Verlauf/Replayspeicher |

