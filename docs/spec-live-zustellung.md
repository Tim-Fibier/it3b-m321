# Live-Zustellung — Spezifikation

**Modul M321 · Klasse IT3b · Stand 13.11.2026**

Diese Spezifikation legt das Zielverhalten der Live-Zustellung fest. Der vorhandene Code in `chat-service` enthält bereits den Anfang des Nachrichtenwegs (`POST /messages`, `chat.persist` und `chat.delivery`); Live-Queue, SSE-Endpunkt und Wiederaufnahme werden hier als Bauziel spezifiziert.

---

## 1. Zweck und Abgrenzung

Der `chat-service` nimmt Nachrichten entgegen und verteilt sie unabhängig auf den Schreibweg `chat.persist` und den Live-Weg über `chat.delivery`. Jede `chat-service`-Instanz bindet eine eigene begrenzte Queue an den Fanout-Exchange und bedient daraus die SSE-Streams ihrer Clients. Die Instanz filtert die Nachrichten nach der `roomId` des jeweiligen Streams.

Nicht Teil dieser Aufgabe sind Keycloak, `web-gateway`, React, JavaFX, Räume anzulegen und Mitglieder einzuladen. Es gibt keine Raumverwaltung und keine Mitgliedschaftsprüfung; die Live-Zustellung verwendet die im Request angegebene `roomId` als Filter.

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

Der Stream-Endpunkt ist `GET /stream?roomId=<UUID>`. Die Antwort hat den Content-Type `text/event-stream` und verwendet Server-Sent Events (SSE). Die Instanz filtert die von ihrer Live-Queue empfangenen Nachrichten auf die angefragte `roomId` und sendet passende Nachrichten als SSE-Ereignisse. `data:` enthält dieselbe JSON-Nachricht mit den sechs Feldern wie auf `chat.persist`.

Ein Ereignis hat dieses Format; eine Leerzeile beendet das Ereignis:

```text
id: <Nachrichten-UUID>
data: {"id":"<Nachrichten-UUID>","roomId":"<Raum-UUID>","senderId":"anna","senderName":"Anna Muster","content":"Hallo","sentAt":"2026-11-13T08:30:00Z"}

```

`id:` ist die Nachrichten-ID aus `ChatMessage.id`; `data:` ist das JSON der Nachricht. Beim Verbindungsaufbau darf ein SSE-Kommentar-Frame wie `: verbunden` gesendet werden. Kommentare beginnen mit `:` und sind kein Chat-Ereignis. Das erste Chat-Ereignis ist die nächste passende Nachricht; beim Verbindungsaufbau wird keine künstliche Begrüssungsnachricht und keine Historie als Snapshot gesendet.

Bei einer automatischen SSE-Neuverbindung enthält `Last-Event-ID` die UUID aus `id:` der zuletzt empfangenen Nachricht. Der Server sucht diese UUID im dauerhaften Nachrichtenverlauf und liefert die danach gespeicherten Nachrichten nach. Für die Reihenfolge gilt `(sent_at, id)` aufsteigend; `id` ist der eindeutige Tie-Breaker bei identischem Zeitstempel. Während des Nachlieferns werden neue Live-Nachrichten zwischengespeichert, mit dem nachgelieferten Verlauf zusammengeführt und anhand ihrer Nachrichten-ID dedupliziert. Erst danach wechselt der Stream auf die laufende Live-Auslieferung. So entstehen beim Übergang weder Lücken noch doppelte Ereignisse.

Der gemeinsame Nachrichtenverlauf ist die Tabelle `message` in PostgreSQL, die der `batch-writer` aus `chat.persist` befüllt. Er wird verwendet statt eines lokalen Puffers in einer `chat-service`-Instanz, weil der Client sich nach einer Unterbrechung an einer anderen Instanz verbinden kann. Ein lokaler Puffer wäre dort nicht verfügbar und ginge beim Neustart oder Löschen der Instanz verloren.

Ist `Last-Event-ID` keine UUID, wird der Request mit `400 Bad Request` abgewiesen. Ist die UUID syntaktisch gültig, aber in der Tabelle nicht vorhanden oder gehört sie nicht zur angefragten `roomId`, kann der Server die Fortsetzungsposition nicht zuverlässig bestimmen; er antwortet mit `409 Conflict` statt stillschweigend Nachrichten zu überspringen. Ist PostgreSQL beim Aufbau der Wiederaufnahme nicht erreichbar, antwortet der Server vor Beginn des Streams mit `503 Service Unavailable`; der Client kann mit derselben `Last-Event-ID` erneut verbinden. Die Replay-Garantie gilt für Nachrichten, die bis zur Wiederaufnahme in `message` persistiert sind. Für den Übergang werden zuerst Live-Nachrichten gepuffert, dann der Datenbankverlauf ab dem Cursor gelesen und die Mengen über `id` zusammengeführt.

`roomId` muss vorhanden und als UUID parsebar sein. Fehlt der Parameter oder ist die UUID ungültig, wird der Request vor dem Öffnen des Streams mit HTTP `400 Bad Request` abgewiesen. Eine syntaktisch gültige UUID wird nicht auf Existenz oder Mitgliedschaften geprüft, weil Räume anzulegen und Mitglieder einzuladen ausdrücklich ausserhalb dieser Aufgabe liegen.

---

## 3. Queues und Exchange

| Baustein | Name | Lebensdauer | Grenze | Was an der Grenze passiert | Begründung |
|---|---|---|---|---|---|
| Live-Queue pro `chat-service`-Instanz | `chat.live.<HOSTNAME>`, z. B. `chat.live.a2d450aeb2a5` | Exklusiv für die AMQP-Verbindung der Instanz; wird beim Schliessen der Verbindung automatisch gelöscht. Nachrichten-TTL: 30 s. | `x-max-length = 950` bereitliegende Nachrichten plus Prefetch 50 unbestätigte Nachrichten: höchstens 1000 insgesamt | Bei voller Queue verwirft RabbitMQ standardmässig die ältesten bereitliegenden Nachrichten (`drop-head`); Nachrichten, die ihre TTL überschreiten, laufen ebenfalls ab. Die bis zu 50 bereits ausgelieferten/unbestätigten Nachrichten zählen nicht zu den 950 bereitliegenden. | Der HOSTNAME ist im Docker-Container automatisch pro Instanz verschieden und muss in `docker-compose.yml` nicht pro Replikat konfiguriert werden. Die begrenzte Queue puffert einen langsamen Consumer, ohne unbegrenzt Speicher zu belegen. Live-Zustellung darf bei Überlastung alte Ereignisse verlieren; der Persistenzpfad bleibt separat. |
| Live-Exchange | `chat.delivery` | Dauerhaft; wird beim Start von `chat-service` deklariert | Keine eigene Speicherkapazität oder Aufbewahrung | Verteilt an alle aktuell gebundenen Live-Queues; der Exchange selbst puffert nicht | Fanout sorgt dafür, dass jede laufende `chat-service`-Instanz eine Kopie erhält und selbst nach `roomId` filtern kann. |
| Schreib-Queue (bleibt) | `chat.persist` | Durable classic Queue | Im batch-writer Paketbildung bis 500 Nachrichten oder 200 ms; keine projektspezifische Queue-Längengrenze konfiguriert | Nachrichten warten auf Verarbeitung; Fehlerbehandlung gemäss `docs/spec-batch-writer.md` | Der Persistenzpfad bleibt unabhängig von der begrenzten Live-Queue. |
| Dead-Letter-Queue | `chat.dlq` | Durable classic Queue | Keine projektseitig konfigurierte Kapazitätsgrenze | Vom batch-writer endgültig abgelehnte Nachrichten werden hier abgelegt | Dient Fehlerfällen der Persistenz; Live-Überlastung wird nicht in die DLQ umgeleitet. |

**Queue-Namensentscheid:** Der Name `chat.live.<HOSTNAME>` folgt der ursprünglichen IT3b-Planung (`git show 0793dda:PLANUNG.md`, Abschnitt 2.2). Docker vergibt jedem Container einen eigenen Hostnamen (kurze Container-ID); daher erhalten auch mit `--scale chat-service=2` beide Instanzen automatisch verschiedene Queue-Namen. Eine pro Instanz fest eingetragene Queue in `docker-compose.yml` wäre beim Skalieren nicht praktikabel, weil die Replikate dieselben Compose-Einträge verwenden.

**Messbeleg:** Vorversuch vom 13.11.2026 mit zwei Spring-Boot-Instanzen gegen `rabbitmq:3.13-management`; Live-Queue exklusiv, `x-max-length=950`, Prefetch 50 und TTL 30 s. Bei pausierter Instanz A und 5000 über B gesendeten Nachrichten erreichte A's Queue nie mehr als 1000 insgesamt (950 bereit + 50 unbestätigt); B erhielt alle 5000. Der Versuch belegt die gemessene Höchstgrenze, nicht das Verhalten bei Verwerfen. RabbitMQ verwendet bei `x-max-length` standardmässig `drop-head` und verwirft damit die ältesten bereitliegenden Nachrichten; siehe [RabbitMQ Queue Length Limit](https://www.rabbitmq.com/docs/maxlength).

---

## 4. Verhalten

### 4.1 Normalfall: der Weg einer Nachricht vom POST bis zum data:

1. Ein Aufrufer sendet einen gültigen Request an `POST /messages` im `chat-service`.
2. Bean Validation prüft, dass `roomId` vorhanden ist und `senderId`, `senderName` sowie `content` nicht leer sind.
3. `MessageService` vergibt eine UUID und einen Server-Zeitstempel und baut daraus ein `ChatMessage`.
4. `MessagePublisher` veröffentlicht das JSON zuerst nach `chat.persist`, dann an `chat.delivery` mit leerem Routing-Key.
5. Bei erfolgreicher Verarbeitung antwortet der Controller mit `202 Accepted` und gibt `id` sowie `sentAt` zurück.
6. RabbitMQ verteilt die Nachricht an die gebundenen `chat.live.<HOSTNAME>`-Queues.
7. Jede `chat-service`-Instanz filtert ihre Kopie nach der `roomId` des offenen Streams und schreibt passende Nachrichten als SSE-Ereignis (`id:` Nachrichten-ID, `data:` JSON) in die Verbindung.

Die Antwort `202` bedeutet angenommen und veröffentlicht, nicht: in der Datenbank gespeichert oder bereits von einem SSE-Client empfangen. Die beiden RabbitMQ-Veröffentlichungen erfolgen nacheinander und sind keine gemeinsame Transaktion. Der vorhandene Code aktiviert keine Publisher Confirms. Fällt das zweite Senden aus, kann das erste bereits erfolgreich in `chat.persist` liegen, obwohl der Aufrufer einen Fehler erhält.

### 4.2 Zwei Instanzen (S3)

Vor dem Test sind zwei `chat-service`-Instanzen mit je einer eigenen Live-Queue an `chat.delivery` gebunden. Ein SSE-Client abonniert Raum A und ein zweiter Client Raum B; pro Instanz werden 200 Nachrichten erzeugt. Jede Instanz erhält durch Fanout eine Kopie jeder Nachricht. Der `roomId`-Filter sorgt dafür, dass der Client in Raum B keine Nachrichten aus Raum A erhält und umgekehrt. Weil pro Instanz genau eine Queue-Bindung eine Kopie erhält und der Consumer jede Zustellung einmal an den passenden lokalen Stream schickt, trifft jede Nachricht beim Client des richtigen Raums genau einmal ein.

Das ist Fanout/Publish-Subscribe, nicht Competing Consumers: bei `chat.persist` teilen sich Verbraucher Nachrichten, bei den Live-Queues erhält jede Instanz eine Kopie. Die 1000 nacheinander veröffentlichten Testnachrichten des Vorversuchs kamen in diesem Versuch ohne ausser-der-Reihe-Beobachtung an; daraus folgt keine allgemeine Reihenfolgegarantie für mehrere Produzenten.

### 4.3 Zurück auf eine Instanz (S4)

Vor dem Herunterskalieren sind genau zwei Live-Queues an `chat.delivery` gebunden, jede mit genau einem Consumer. Nach dem Zurückskalieren auf eine `chat-service`-Instanz ist die Queue der beendeten Instanz gelöscht; innerhalb von höchstens 30 s ist nur noch eine Live-Queue mit einem Consumer vorhanden. `chat.persist` und `chat.dlq` bleiben erhalten und unverändert, weil sie nicht die exklusiven Live-Queues sind.

Prüfung: `docker compose exec -T rabbitmq rabbitmqctl -q list_bindings` zeigt zuerst zwei Bindings für `chat.delivery`; `docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages consumers` zeigt anschliessend nur die Live-Queue der laufenden Instanz mit einem Consumer sowie weiterhin `chat.persist` und `chat.dlq`. Im Vorversuch wurde die Queue bei `docker stop` bzw. `docker kill` nach 3 s gelöscht; das liegt innerhalb der geforderten 30 s.

### 4.4 Datenbank und batch-writer weg (S5)

`chat-service` und RabbitMQ bleiben verfügbar, während `batch-writer` und PostgreSQL gestoppt sind. 100 Nachrichten werden gesendet und innerhalb von höchstens 5 s über den Live-Pfad an den verbundenen Client zugestellt. Das funktioniert, weil die Live-Zustellung nicht auf einen Datenbankzugriff wartet. Die 100 Persistenzkopien sammeln sich in der durable Queue `chat.persist`.

Nach dem Neustart von PostgreSQL und `batch-writer` verarbeitet der batch-writer die wartenden Nachrichten; alle 100 stehen danach in `message`. Die Live-Queue ist nicht der Persistenzpuffer und `chat.dlq` bleibt für diesen Umgebungs-Ausfall unverändert.

### 4.5 Hängende Instanz (S6)

Eine `chat-service`-Instanz wird pausiert, die andere bleibt aktiv. Während über die aktive Instanz 5000 Nachrichten gesendet werden, erhält deren Live-Queue weiterhin ihre Nachrichten und der verbundene Client bekommt sie live. Die Queue der pausierten Instanz wächst auf höchstens 950 bereitliegende plus 50 unbestätigte Nachrichten. Bei mehr Rückstau verwirft RabbitMQ nach der konfigurierten Längenbegrenzung die ältesten bereitliegenden Nachrichten; die Live-Zustellung ist in diesem Ausfallfall begrenzt und nicht verlustfrei.

Wird die pausierte Instanz fortgesetzt, nimmt sie die Verarbeitung wieder auf und stellt neue passende Nachrichten zu. Die Queue wird nach RabbitMQ-Verbindungsausfall länger als den Herzschlag gelöscht; beim Wiederverbinden wird sie neu angelegt und neu an `chat.delivery` gebunden. Während der nicht verbundenen Zeit verpasste oder abgelaufene Live-Nachrichten werden nicht aus dieser Queue nachgeholt; eine spätere Wiederaufnahme läuft über `Last-Event-ID` und den persistenten Nachrichtenverlauf.

Messwerte des Vorversuchs vom 13.11.2026: Bei 5000 Nachrichten blieb die Queue der pausierten Instanz bei höchstens 1000, die andere Instanz erhielt alle 5000. Nach kurzer Pause stellte die fortgesetzte Instanz neue Nachrichten wieder zu. Nach 150 s Pause (länger als der RabbitMQ-Herzschlag) wurde ihre Queue nach 132 s gelöscht; nach dem Fortsetzen wurde sie innerhalb von 2 s neu angelegt und die Zustellung lief ohne Fehler weiter.

### 4.6 Neuverbindung mit Last-Event-ID (S7)

Der Server verwendet die Tabelle `message` in PostgreSQL als gemeinsamen, dauerhaften Replay-Verlauf. Ein lokaler Arbeitsspeicher-Puffer wäre falsch, weil der Client nach dem Trennen an einer anderen `chat-service`-Instanz landen kann und ein Prozessneustart den Puffer löschen würde. Die Tabelle wird bereits vom batch-writer beschrieben; dafür muss der `chat-service` eine lesende Abfrage auf diesen Verlauf erhalten.

Beim Reconnect wird `Last-Event-ID` als UUID in `message` für die angefragte `roomId` gesucht. Der Server liest danach die Nachrichten mit `(sent_at, id)` grösser als die Position der Cursor-Zeile, aufsteigend sortiert. Für S7 ist die Vorgabe: nach einer Unterbrechung, während der genau 50 Nachrichten gesendet wurden, liefert der Server genau diese 50 Ereignisse in dieser Reihenfolge nach, keine doppelt, und danach weiter eintreffende Ereignisse live.

Für eine lückenlose Übergabe richtet der Server zuerst den Live-Consumer ein und puffert dessen Ereignisse, bevor er den Datenbank-Replay startet. Er liefert die historischen Zeilen ab dem Cursor, führt währenddessen gepufferte Live-Ereignisse anhand ihrer UUID mit dem Verlauf zusammen und dedupliziert; Ereignisse nach dem Replay-Wasserzeichen werden in ihrer `(sent_at, id)`-Reihenfolge ausgegeben. Danach läuft die Zustellung direkt aus der Live-Queue weiter. So gehen während der Replay-Abfrage neu eintreffende Ereignisse nicht verloren und Ereignisse, die sowohl im Verlauf als auch im Live-Puffer stehen, werden nur einmal gesendet.

Ist die Cursor-ID unbekannt oder nicht Teil des angefragten Raums, antwortet der Server mit `409 Conflict`; er beginnt keinen Stream mit stillschweigend übersprungenen Nachrichten. Ist PostgreSQL beim Reconnect nicht erreichbar, antwortet er vor dem Streamstart mit `503 Service Unavailable`; der Client behält die `Last-Event-ID` und kann erneut versuchen. Der Replay kann nur Nachrichten nachliefern, die bereits in `message` persistiert sind. Der S7-Test wartet deshalb vor dem Reconnect, bis die 50 Nachrichten durch den batch-writer gespeichert wurden. Die asynchrone Verzögerung zwischen Veröffentlichung nach `chat.persist` und Datenbank-Commit ist kein Versprechen einer sofortigen Historienlesbarkeit.

---

## 5. Abnahmekriterien

Die Szenarien S1 bis S8 entsprechen der Aufgabenliste für die Live-Zustellung. Für die Befehle mit Platzhaltern werden `ROOM_A`, `ROOM_B` und die Container-IP-Adressen der jeweiligen `chat-service`-Instanzen vor dem Test gesetzt. SSE-Clients und Sender laufen als Container im Docker-Netz `chat-net`, da kein Dienst einen Port auf dem Host veröffentlicht.

| Nr | Szenario | Befehl | Erwartet |
|---|---|---|---|
| S1 | Tests im Wurzelverzeichnis | `mvn clean test` | `BUILD SUCCESS`; alle Tests grün. |
| S2 | Frischer Klon, Umgebungsdatei anlegen und Stack starten | `cp .env.example .env && docker compose up -d --build && docker compose ps` | Build erfolgreich; alle vorgesehenen Dienste laufen. Kein Dienst veröffentlicht einen Port auf den Host. |
| S3 | Zwei Instanzen, Clients in Raum A und B, je 200 Nachrichten pro Instanz | `docker compose up -d --scale chat-service=2`; danach zwei SSE-Clients mit `docker run --rm --network chat-net curlimages/curl -N "http://<IP_A>:8080/stream?roomId=$ROOM_A"` und entsprechend für B starten; je 200 `POST /messages` an jede Instanz senden | Beide Live-Queues sind an `chat.delivery` gebunden. Jeder Client erhält alle Nachrichten seines Raums genau einmal und keine Nachricht des anderen Raums. |
| S4 | Zwei Instanzen, dann auf eine zurückskalieren | `docker compose exec -T rabbitmq rabbitmqctl -q list_bindings`; `docker compose up -d --scale chat-service=1`; danach `docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages consumers` und erneut `list_bindings` | Vorher zwei Live-Queue-Bindings mit je einem Consumer; spätestens nach 30 s nur noch eine Live-Queue mit einem Consumer. `chat.persist` und `chat.dlq` bleiben bestehen. Vorversuch: Queue nach `docker stop`/`docker kill` in 3 s gelöscht. |
| S5 | `batch-writer` und Postgres stoppen, 100 Nachrichten senden, danach starten | `docker compose stop batch-writer postgres`; 100 `POST /messages` senden und SSE-Client beobachten; dann `docker compose start postgres batch-writer`; Datenbank mit `SELECT count(*) FROM message ...` prüfen | Alle 100 Nachrichten innerhalb von höchstens 5 s beim Live-Client. Während des Ausfalls bleiben Persistenzkopien in `chat.persist`; nach dem Neustart sind alle 100 in `message`. |
| S6 | Eine Instanz pausieren, 5000 Nachrichten senden, dann fortsetzen | `docker compose ps -q chat-service` (eine ID auswählen); `docker pause <CONTAINER_ID>`; 5000 Nachrichten über die andere Instanz senden; `docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages consumers`; danach `docker unpause <CONTAINER_ID>` | Queue der pausierten Instanz höchstens 1000 insgesamt (950 bereit + 50 Prefetch); die aktive Instanz liefert ihre 5000 Nachrichten weiter. Nach dem Fortsetzen kommen neue passende Nachrichten wieder live. Rückstau oberhalb der Grenze darf alte Live-Nachrichten verlieren. |
| S7 | Client trennt, 50 Nachrichten, Reconnect mit `Last-Event-ID` | SSE-Client mit `curl -N` verbinden und letzte `id:` merken; Verbindung trennen; 50 Nachrichten senden und auf Speicherung in `message` warten; `curl -N -H 'Last-Event-ID: <UUID>' "http://<IP>:8080/stream?roomId=<ROOM_UUID>"` | Genau die 50 fehlenden Nachrichten, geordnet nach `(sent_at, id)`, keine doppelt; danach werden neue Nachrichten wieder live zugestellt. |
| S8 | Codestil im `chat-service`; `.env` nicht im Repository | `grep -rnE '\.stream\(\)|Stream\.of|Collectors|\.forEach\(|IntStream' chat-service/src; git ls-files .env`; zusätzlich denselben Kommentar-Check wie im Szenarien-Skript auf `chat-service/src` anwenden | Keine unerlaubten Stream-Stellen; `.env` wird nicht von Git verfolgt; jede Klasse und Methode hat einen erklärenden Kommentar. |

Die Befehle `rabbitmqctl list_bindings` und `rabbitmqctl list_queues name messages consumers` prüfen die Queue-Bindings, Queue-Tiefe und Consumerzahl. Der Abnahmetest für S3–S7 muss die empfangenen SSE-Ereignisse nach `id` und `roomId` auswerten; ein erfolgreicher HTTP-Request allein weist die Live-Zustellung nicht nach.

---

## 6. Entscheide und offene Punkte

| Entscheid | Begründung | Verworfen |
|---|---|---|
| Eigene exklusive Queue `chat.live.<HOSTNAME>` pro `chat-service`-Instanz | Fanout muss jede Instanz erreichen, weil Clients an unterschiedlichen Instanzen hängen; HOSTNAME ist pro Container verschieden und benötigt keine Compose-Sonderkonfiguration | Eine gemeinsame Live-Queue als Competing Consumers: eine Nachricht würde nur bei einer Instanz ankommen |
| Queue-Limit 950 bereitliegend plus Prefetch 50 statt Limit 1000 plus Prefetch 50 | Gesamtobergrenze ist damit 1000 Nachrichten (950 bereit + höchstens 50 unbestätigt); entspricht dem Vorversuch | `x-max-length=1000` zusätzlich zu Prefetch 50: könnte insgesamt 1050 Nachrichten binden |
| Queue exklusiv statt mit `x-expires` | Exklusivität bindet die Queue an die AMQP-Verbindung der Instanz und löscht sie beim Verbindungsverlust. Messung: nach `docker stop`/`docker kill` nach 3 s gelöscht; nach Herzschlag-Ausfall gelöscht und neu angelegt | Nur `x-expires`: zeitbasierte Löschung kann eine aktive Queue betreffen und richtet sich nicht direkt nach dem Verbindungslebenszyklus |
| `x-max-length=950` verwendet RabbitMQ-Standardverhalten `drop-head` | Bei der Grenze werden die ältesten bereitliegenden Nachrichten verworfen; kurze Live-Zustellung bleibt begrenzt und neuere Ereignisse können weiter eintreffen | Unbegrenzte Queue oder Ablehnung neuer Nachrichten; beides passt schlechter zum begrenzten Live-Rückstau |
| Raumfilter in jeder `chat-service`-Instanz | Jede Instanz erhält per Fanout alle Nachrichten; der lokale Filter liefert nur Nachrichten mit passender `roomId` an den Stream | Routing nach Raum im Exchange: der verwendete Exchange ist Fanout und die Aufgabe verwaltet keine Räume oder Bindings pro Raum |
| SSE-Replay-Quelle ist die gemeinsame Tabelle `message` in PostgreSQL | Der Verlauf ist instanzunabhängig und überlebt Prozess-/Queue-Neustarts; ein Client kann sich an einer anderen Instanz verbinden | Lokaler Speicherpuffer: nicht verfügbar an einer anderen Instanz und nicht dauerhaft |
| Replay ordnet nach `(sent_at, id)` und dedupliziert nach Nachrichten-UUID | `sent_at` liefert die zeitliche Ordnung, `id` löst gleiche Zeitstempel deterministisch auf; ID-Deduplizierung verbindet Replay und Live-Puffer | UUID lexikografisch als Zeitordnung behandeln oder während des Replays direkt ohne Puffer live senden |
| Fehlender/ungültiger `roomId` bzw. ungültige Replay-ID ergibt `400`; unbekannter oder raumfremder Replay-Cursor ergibt `409`; Datenbankausfall beim Replay ergibt `503` | Der Server überspringt keine unbekannte Position stillschweigend und der Client kann mit demselben Cursor wiederholen | Leeren Stream oder künstlichen Begrüssungs-Event zurückgeben, wodurch der Client Lücken nicht erkennen könnte |

### Offene Punkte

| Nr | Punkt | Konsequenz / nächster Schritt |
|---|---|---|
| O1 | Publisher Confirms sind im vorhandenen `MessagePublisher` nicht aktiviert | Das Veröffentlichen nach `chat.persist` und `chat.delivery` ist nicht atomar; Fehlerfenster dokumentieren oder später gezielt mit Confirm-/Outbox-Strategie härten. |
| O2 | Der vorhandene `chat-service`-Code besitzt noch keinen SSE-Endpunkt, Live-Queue-Consumer oder Replay-Leser | Die hier spezifizierten Zielverträge implementieren und S1–S8 gegen den Compose-Stack abnehmen. |
| O3 | Replay liest nur bereits persistierte Nachrichten | Bei einem Reconnect vor dem Datenbank-Commit kann die Historie noch hinter dem Live-Pfad liegen; für strengere Read-after-publish-Garantie wäre ein Persistenz-Wasserzeichen/Barrier-Mechanismus nötig. |

---

## Quellen

- `chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessagePublisher.java`
- `chat-service/src/main/java/ch/benedict/m321/chatservice/config/RabbitConfig.java`
- `chat-service/src/main/java/ch/benedict/m321/chatservice/dto/ChatMessage.java`
- `docs/spec-batch-writer.md` (Persistenzvertrag und Tabelle `message`)
- Ursprüngliche IT3b-Planung: `git show 0793dda:PLANUNG.md`, Abschnitt 2.2
- Vorversuch vom 13.11.2026 (Messwerte wie in der Aufgabenbeschreibung; ursprünglich ausserhalb dieses Repositories durchgeführt)
- [RabbitMQ Queue Length Limit](https://www.rabbitmq.com/docs/maxlength) (Standardverhalten `drop-head`)
