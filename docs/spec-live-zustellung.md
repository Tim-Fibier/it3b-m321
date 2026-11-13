# Live-Zustellung — Spezifikation

**Modul M321 · Klasse IT3b · Stand 13.11.2026**

Grundlage: die Vorgaben zur Live-Zustellung und der Vorversuch vom 13.11.2026. Der vorhandene Code in `chat-service` beschreibt den bereits bestehenden Anfang des Nachrichtenwegs; diese Spezifikation legt das Zielverhalten der Live-Zustellung fest.

---

## 1. Zweck und Abgrenzung

Der `chat-service` nimmt Nachrichten entgegen und verteilt sie unabhängig auf den Schreibweg `chat.persist` und den Live-Weg über `chat.delivery`. Jede `chat-service`-Instanz besitzt dafür eine eigene begrenzte Live-Queue und stellt die passenden Nachrichten für einen SSE-Stream bereit. So können mehrere Instanzen parallel Nachrichten an die Clients ausliefern, die mit ihnen verbunden sind.

Nicht Teil dieser Aufgabe sind Keycloak, `web-gateway`, React, JavaFX, Räume anzulegen und Mitglieder einzuladen. Die Live-Zustellung baut keine dieser Komponenten und keine Raumverwaltung; sie verwendet eine vom Aufrufer angegebene `roomId`, um Nachrichten für den Stream zu filtern.

---

## 2. Vertrag

### 2.1 Was am Exchange ankommt

Der `chat-service` veröffentlicht die Nachricht mit `MessagePublisher.publish()` zuerst auf `chat.persist` und danach auf dem Exchange `chat.delivery`. Für den Exchange verwendet er den leeren Routing-Key; bei einem Fanout-Exchange wird der Routing-Key ignoriert. Quelle: `chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessagePublisher.java`.

| Eigenschaft | Wert | Beleg |
|---|---|---|
| Exchange | `chat.delivery` | `QueueNames.DELIVERY_EXCHANGE` |
| Typ | Fanout, dauerhaft (`durable = true`), nicht automatisch löschbar | `RabbitConfig.deliveryExchange()` |
| Erzeuger | `chat-service` | `MessagePublisher.publish()` |
| Routing-Key | leer (`""`); beim Fanout ohne Bedeutung | `MessagePublisher.FANOUT_ROUTING_KEY` |
| Format | JSON, erzeugt durch `Jackson2JsonMessageConverter` | `RabbitConfig.jsonMessageConverter()` |
| Nutzdaten | `id`, `roomId`, `senderId`, `senderName`, `content`, `sentAt` | `dto/ChatMessage.java` |

Die Nachricht ist ein `ChatMessage`-Record. `id` und `sentAt` vergibt der `chat-service`; `roomId`, Absender und Text kommen aus dem Request. `sentAt` wird als ISO-8601-Zeitpunkt aus einem Java-`Instant` serialisiert. Der JSON-Vertrag besteht aus den sechs Feldern des Records.

Ein Fanout-Exchange speichert Nachrichten nicht selbst. Er verteilt eine veröffentlichte Nachricht an alle Queues, die zu diesem Zeitpunkt daran gebunden sind. Deshalb bindet jede Instanz ihre eigene Live-Queue an `chat.delivery`; eine einzelne gemeinsame Queue würde die Nachricht nur an einen konkurrierenden Verbraucher verteilen.

### 2.2 Was am Stream hinausgeht

Der Stream-Endpunkt ist `GET /stream?roomId=<UUID>`. Die Antwort hat den Content-Type `text/event-stream` und verwendet Server-Sent Events (SSE). Der Server filtert die von der Instanz empfangenen Nachrichten auf die angefragte `roomId` und sendet passende Nachrichten als SSE-Ereignisse. Es wird kein eigenes DTO für den Stream eingeführt: `data:` enthält dieselbe JSON-Nachricht mit den sechs Feldern wie auf `chat.persist`.

Ein Ereignis hat dieses Format (eine Leerzeile beendet das Ereignis):

```text
id: <Nachrichten-UUID>
data: {"id":"<Nachrichten-UUID>","roomId":"<Raum-UUID>","senderId":"anna","senderName":"Anna Muster","content":"Hallo","sentAt":"2026-11-13T08:30:00Z"}

```

`id:` ist die Nachrichten-ID aus `ChatMessage.id`; `data:` ist das JSON der Nachricht. Der Server sendet keine synthetische Chat-Nachricht als Begrüssung. Als erstes kann ein SSE-Kommentar-Frame zum Offenhalten/Bestätigen der Verbindung gesendet werden; Kommentare beginnen mit `:` und sind kein Ereignis. Danach ist das erste `data:`-Ereignis die nächste passende Live-Nachricht, die nach dem Verbinden eintrifft. Es wird beim Streamstart keine Chat-Historie als Snapshot gesendet.

Bei automatischer SSE-Neuverbindung kann der Client `Last-Event-ID` mit der ID des zuletzt empfangenen Ereignisses mitsenden. Dieser Header ist ein Wiederaufnahme-Cursor, keine Nachrichten-ID zum Vergleichen nach zeitlicher Reihenfolge. Die Live-Queue ist begrenzt und kein dauerhafter Verlaufsspeicher; die Wiederaufnahme darf daher keine unbegrenzte oder dauerhafte Wiedergabe versprechen. Das konkrete Verhalten für das Nachholen von Ereignissen nach einer Unterbrechung wird in Abschnitt 4.6 festgelegt.

`roomId` muss vorhanden und als UUID parsebar sein. Fehlt der Parameter oder ist die UUID ungültig, wird der Request vor dem Öffnen des Streams mit HTTP `400 Bad Request` abgewiesen. Eine syntaktisch gültige UUID wird nicht auf Existenz oder Mitgliedschaften geprüft, weil Räume anlegen und Mitglieder einladen ausdrücklich ausserhalb dieser Aufgabe liegen.

---

## 3. Queues und Exchange

| Baustein | Name | Lebensdauer | Grenze | Was an der Grenze passiert | Begründung |
|---|---|---|---|---|---|
| Live-Queue pro `chat-service`-Instanz | `chat.live.<HOSTNAME>`, z. B. `chat.live.a2d450aeb2a5` | Exklusiv für die AMQP-Verbindung der Instanz; wird beim Schliessen der Verbindung automatisch gelöscht. Nachrichten haben eine TTL von 30 s. | `x-max-length = 950` Nachrichten bereit in der Queue plus Prefetch 50 unbestätigte Nachrichten: höchstens 1000 insgesamt | Bei voller Queue verwirft RabbitMQ die ältesten bereitliegenden Nachrichten (drop-head). Die bis zu 50 bereits ausgelieferten/unbestätigten Nachrichten zählen nicht zu den 950 bereitliegenden. Abgelaufene Nachrichten werden ebenfalls nicht mehr zugestellt. | Der HOSTNAME ist im Docker-Container automatisch pro Instanz verschieden und muss in `docker-compose.yml` nicht pro Replikat konfiguriert werden. Die kurze, begrenzte Queue puffert einen vorübergehend langsamen Consumer, ohne unbegrenzt Speicher zu belegen. Live-Zustellung darf bei Überlastung alte Ereignisse verlieren; der Persistenzpfad bleibt separat. |
| Live-Exchange | `chat.delivery` | Dauerhaft; wird beim Start von `chat-service` deklariert | Keine eigene Speicherkapazität oder Aufbewahrung | Verteilt an alle aktuell gebundenen Live-Queues; der Exchange selbst puffert nicht | Fanout sorgt dafür, dass jede laufende `chat-service`-Instanz eine Kopie erhält und selbst nach `roomId` filtern kann. |
| Schreib-Queue (bleibt) | `chat.persist` | Durable classic Queue | Im batch-writer Paketbildung bis 500 Nachrichten oder 200 ms; keine projektspezifische Queue-Längengrenze konfiguriert | Nachrichten warten auf Verarbeitung; dauerhafte Schreibfehler werden nach den Regeln aus `docs/spec-batch-writer.md` behandelt | Der Persistenzpfad bleibt unabhängig von der verlustbehafteten, begrenzten Live-Queue. |
| Dead-Letter-Queue | `chat.dlq` | Durable classic Queue | Keine projektseitig konfigurierte Kapazitätsgrenze | Vom batch-writer endgültig abgelehnte Nachrichten werden hier abgelegt | Dient Fehlerfällen der Persistenz; Live-Überlastung ist kein Grund, eine Nachricht nach `chat.dlq` zu schicken. |

**Queue-Namensentscheid:** Der Name `chat.live.<HOSTNAME>` folgt der ursprünglichen IT3b-Planung (`git show 0793dda:PLANUNG.md`, Abschnitt 2.2). Docker vergibt jedem Container einen eigenen Hostnamen (kurze Container-ID); daher erhalten auch mit `--scale chat-service=2` beide Instanzen automatisch verschiedene Queue-Namen. Eine pro Instanz fest eingetragene Queue in `docker-compose.yml` wäre beim Skalieren nicht praktikabel, weil die Replikate dieselben Compose-Einträge verwenden.

**Beleg für die Queue-Grenze:** Vorversuch vom 13.11.2026 mit zwei Spring-Boot-Instanzen und `rabbitmq:3.13-management`; Live-Queue exklusiv, `x-max-length=950`, Prefetch 50 und TTL 30 s. Bei pausierter Instanz A und 5000 über B gesendeten Nachrichten erreichte A höchstens 1000 wartende und unbestätigte Nachrichten (950 bereit + 50 Prefetch); B erhielt alle 5000. Bei voller Queue wurden die ältesten Nachrichten verworfen. Weitere Messwerte und Szenarien stehen in Abschnitt 4.

---

## 4. Verhalten

### 4.1 Normalfall: der Weg einer Nachricht von POST bis zum data:

1. Ein Aufrufer sendet einen gültigen Request an `POST /messages` im `chat-service`.
2. Bean Validation prüft, dass `roomId` vorhanden ist und `senderId`, `senderName` sowie `content` nicht leer sind.
3. `MessageService` vergibt eine UUID und einen Server-Zeitstempel und baut daraus ein `ChatMessage`.
4. `MessagePublisher` veröffentlicht das JSON zuerst nach `chat.persist`, dann an `chat.delivery` mit leerem Routing-Key.
5. Bei erfolgreicher Verarbeitung antwortet der Controller mit `202 Accepted` und gibt `id` sowie `sentAt` zurück.
6. RabbitMQ verteilt die Nachricht an die gebundenen `chat.live.<HOSTNAME>`-Queues.
7. Jede `chat-service`-Instanz nimmt ihre Kopie aus der eigenen Queue, filtert auf die `roomId` des offenen Streams und schreibt passende Nachrichten als SSE-Ereignis (`id:` Nachrichten-ID, `data:` JSON) in die Verbindung.

Die Antwort `202` bedeutet angenommen und veröffentlicht, nicht: in der Datenbank gespeichert oder bereits von einem SSE-Client empfangen. Die beiden RabbitMQ-Veröffentlichungen erfolgen nacheinander und sind keine gemeinsame Transaktion. Der Code aktiviert keine Publisher Confirms. Fällt das zweite Senden aus, kann das erste bereits erfolgreich in `chat.persist` liegen, obwohl der Aufrufer einen Fehler erhält.

### 4.2 Zwei Instanzen (S3)

Für den Live-Pfad besitzt jede `chat-service`-Instanz eine eigene Queue (`chat.live.<HOSTNAME>`) und bindet sie an `chat.delivery`. Der Fanout-Exchange schickt eine Kopie jeder Nachricht an beide Queues. Beide Instanzen filtern nach `roomId`; damit kann ein Client seine SSE-Verbindung an jeder der Instanzen halten und erhält dort passende Nachrichten. Die Queues sind keine Competing Consumers miteinander: anders als `chat.persist` soll jede Instanz jede Live-Nachricht sehen.

Vorversuch vom 13.11.2026: Die Hostnamen zweier Instanzen waren verschieden (z. B. `a2d450aeb2a5` und `15d07e9b21b8`). Bei pausierter Instanz A und 5000 über B gesendeten Nachrichten blieb A's Queue bei höchstens 1000 (950 bereit + 50 Prefetch); B erhielt alle 5000. Bei 1000 einzeln nacheinander gesendeten Nachrichten wurden 0 ausser der Reihe beobachtet. Das ist ein Messwert dieses Versuchs, keine allgemeine Reihenfolgegarantie für mehrere Produzenten.

### 4.3 Zurück auf eine Instanz (S4)

Wenn die Anwendung wieder mit einer Instanz läuft, bleibt deren Live-Queue an `chat.delivery` gebunden; Nachrichten werden an diese Instanz verteilt. Die Queue der beendeten zweiten Instanz wird beim Schliessen ihrer exklusiven AMQP-Verbindung gelöscht. Die bestehende Instanz braucht keine Umkonfiguration und empfängt weiter.

Vorversuch vom 13.11.2026: Nach dem Fortsetzen einer kurz pausierten Instanz stellte sie neue Nachrichten wieder zu. Nach `docker stop` bzw. `docker kill` war die Queue der beendeten Instanz nach 3 s weg. Das verhindert dauerhaft verwaiste Instanz-Queues.

### 4.4 Datenbank und batch-writer weg (S5)

Live-Zustellung und Persistenz sind zwei getrennte Pfade. Ist Postgres oder der `batch-writer` nicht verfügbar, darf das den Start des `chat-service`, die Annahme der Nachricht und die Zustellung über `chat.delivery` nicht blockieren. `chat.persist` puffert die Persistenznachrichten, bis der batch-writer wieder arbeiten kann; die Live-Queue ersetzt diesen dauerhaften Schreibpuffer nicht.

Vorversuch vom 13.11.2026: Die Anwendung startete normal, obwohl die Datenbank nicht erreichbar war; erst ein tatsächlicher Datenbankzugriff schlug fehl. Für die Live-Zustellung gilt entsprechend: kein synchroner Datenbankzugriff vor dem Senden des SSE-Ereignisses.

### 4.5 Hängende Instanz (S6)

Eine pausierte Instanz kann vorübergehend keine Live-Nachrichten an ihre Clients schreiben. RabbitMQ puffert für diese Instanz höchstens 950 bereitliegende Nachrichten plus 50 unbestätigte Nachrichten. Wird die Grenze überschritten, werden die ältesten bereitliegenden Live-Nachrichten verworfen; neuere Nachrichten können weiterhin in der Queue liegen. Die andere aktive Instanz erhält ihre Fanout-Kopie unabhängig davon und stellt weiter zu.

Vorversuch vom 13.11.2026: Bei 5000 Nachrichten über Instanz B blieb die Queue von pausierter Instanz A bei höchstens 1000; B erhielt alle 5000. Nach kurzer Pause stellte A nach dem Fortsetzen neue Nachrichten wieder zu. Bei 150 s Pause (länger als der RabbitMQ-Herzschlag) wurde A's Queue nach 132 s gelöscht; nach dem Fortsetzen wurde sie innerhalb von 2 s neu angelegt und die Zustellung lief weiter, ohne Fehler. Die Wiederanlage gilt für neue Nachrichten; währenddessen verworfene oder wegen TTL abgelaufene Live-Nachrichten werden nicht nachträglich wiederhergestellt.

### 4.6 Neuverbindung mit Last-Event-ID (S7)

Bei einer SSE-Neuverbindung sendet der Client die zuletzt empfangene Ereignis-ID als `Last-Event-ID`. Sie wird als Resume-Cursor verwendet, damit ein Client wieder an seiner zuletzt bestätigten Position ansetzen kann. Da `chat.live.<HOSTNAME>` exklusiv, kurzlebig und begrenzt ist und alte Nachrichten bei Überlast oder nach TTL verloren gehen können, kann diese Queue allein keine lückenlose Wiederholung über eine Unterbrechung garantieren. Ein allfälliges Nachholen muss aus einem dauerhaften Nachrichtenverlauf erfolgen; die Queue darf nicht als Verlaufsspeicher ausgegeben werden.

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
