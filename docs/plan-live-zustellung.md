# Live-Zustellung — Umsetzungsplan

**Ziel:** Jede Instanz des `chat-service` holt ihre Kopie jeder Nachricht aus einer eigenen Queue
am Fanout-Exchange `chat.delivery` und schiebt sie per Server-Sent Events an die offenen
Verbindungen des passenden Raums. Wer kurz weg war, holt mit `Last-Event-ID` genau das nach, was
ihm fehlt.

**Spec:** [`spec-live-zustellung.md`](spec-live-zustellung.md). Die Abnahmekriterien stehen dort.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP, `SseEmitter` (Spring MVC), `JdbcTemplate`
nur zum Lesen, RabbitMQ 3.13, PostgreSQL 16, JUnit 5, Testcontainers.

## Spielregeln für diesen Plan

- Die KI schreibt den Code, ich gebe pro Schritt den Auftrag, lese das Ergebnis, lasse den Test
  laufen und korrigiere, bevor ich committe.
- Dieser Plan ist committet und gepusht, bevor Schritt 1 beginnt. Danach bleibt der Text stehen.
  Muss ich umplanen, kommt die Änderung als eigener Eintrag mit Änderungsgrund im Abschnitt
  „Änderungen am Plan“ dazu, in einem eigenen Commit.
- Einzige Ausnahme: Das Feld „Geprüft und korrigiert“ fülle ich nach jedem Schritt aus.
- Ein Schritt, ein Thema, ein Commit. Message deutsch, Commits der KI tragen ihre
  `Co-Authored-By`-Zeile.
- Regeln aus `CLAUDE.md`: Code englisch, Kommentare deutsch, keine Streams, keine verschachtelten
  Aufrufe, Kommentar über jeder Klasse und Methode, auch in Tests.

## Entscheide, auf denen der Plan aufbaut

Diese Punkte sind in der Spezifikation begründet. Ändert sich dort einer, ändert sich der Plan.

| Thema | Entscheid |
|---|---|
| Queue-Name | `chat.live.<HOSTNAME>`: pro Instanz ein anderer Wert, ohne Eintrag in `docker-compose.yml` |
| Lebensdauer | nicht dauerhaft und exklusiv: RabbitMQ löscht sie, sobald die Verbindung der Instanz weg ist (S4). Bewusst kein `x-expires`, weil RabbitMQ nicht garantiert, wie schnell danach gelöscht wird |
| Grenze | `x-max-length` 950 plus Prefetch 50: bereitliegend und unbestätigt zusammen nie mehr als 1000 (S6) |
| Bei Überlauf | RabbitMQ wirft die älteste Nachricht weg (`drop-head`). Für die Anzeige richtig: Der Verlauf steht in der Datenbank |
| Alter | `x-message-ttl` 30 s: Eine ältere Live-Nachricht ist wertlos |
| Raumfilter | pro `roomId` eine Liste offener Verbindungen in der Instanz; der Filter passiert im `chat-service`, nicht im Broker |
| Stream-Format | `id:` ist die Nachrichten-ID, `data:` das JSON aus `chat.delivery`, unverändert weitergereicht |
| Nachholen (S7) | aus der Tabelle `message`, weil jede Instanz sie lesen kann, auch wenn der Client die Instanz wechselt. Der `chat-service` liest nur, das Schema gehört weiter dem `batch-writer` |
| Fehler beim Nachholen | `Last-Event-ID` keine UUID: `400`. ID unbekannt oder aus einem anderen Raum: `409`. Datenbank nicht erreichbar: `503`. Alle drei vor dem Start des Streams, damit nie still eine Lücke entsteht |
| Grenze des Nachholens | Nachgeholt wird, was beim Lesen schon in `message` steht. Kein künstliches Warten im Server; der Test für S7 wartet, bis die 50 gespeichert sind |
| Datenbank im `chat-service` | erst beim Nachholen gebraucht, darum kein `depends_on: postgres`: Der Live-Weg läuft ohne Datenbank weiter (S5) |

### Vorversuch vom 13.11.2026

Vor dem Plan habe ich die heiklen Entscheide mit einer Wegwerf-App gegen `rabbitmq:3.13-management`
geprüft, ausserhalb des Repos:

| Frage | Gemessen |
|---|---|
| Startet Spring mit JDBC, wenn die Datenbank fehlt? | ja, erst der Zugriff wirft `CannotGetJdbcConnectionException` |
| Ist `HOSTNAME` pro Instanz verschieden? | ja, z. B. `a2d450aeb2a5` und `15d07e9b21b8` |
| Instanz pausiert, 5000 Nachrichten: höchster Wert `messages` ihrer Queue | 1000 (950 bereit + 50 unbestätigt); die andere Instanz bekam alle 5000 |
| Nach dem Fortsetzen | stellt neue Nachrichten wieder zu |
| Pause länger als der Herzschlag | Queue nach 132 s gelöscht, nach dem Fortsetzen in 2 s neu angelegt, Zustellung läuft weiter |
| `docker stop` bzw. `docker kill` | Queue nach 3 s weg |
| 1000 einzelne HTTP-Anfragen nacheinander | 0 ausser der Reihe |
| Muss man `SseEmitter.send` selbst absichern? | nein, `ResponseBodyEmitter` sperrt seit Spring 6.1 mit einem `writeLock` |

## Reihenfolge auf einen Blick

| # | Schritt | Commit | Warum an dieser Stelle |
|---|---|---|---|
| 1 | Kommentare im Bestand | `docs: Kommentare im chat-service nach CLAUDE.md ergänzen` | S8 prüft den ganzen `chat-service`. Zuerst, damit der neue Code nicht mit dem alten vermischt wird |
| 2 | Eigene Queue pro Instanz | `feat: Live-Queue pro Instanz am Fanout-Exchange anlegen` | ohne Queue verwirft RabbitMQ jede Kopie, alles Weitere hängt daran |
| 3 | Stream-Endpunkt | `feat: GET /stream mit Verbindungen pro Raum` | die andere Seite des Wegs, noch ohne Broker testbar |
| 4 | Queue und Stream verbinden | `feat: Nachrichten aus der Live-Queue an den Raum zustellen` | erst jetzt läuft der Weg vom POST bis zum `data:` |
| 5 | Zwei Instanzen | `test: Verteilung über zwei Instanzen prüfen` | S3 beweist den Grund für die eigene Queue, bevor Grenzen dazukommen |
| 6 | Grenzen und Lebensdauer | `feat: Live-Queue begrenzen und alte Nachrichten verfallen lassen` | S4 und S6. Erst jetzt, weil man den Normalfall davor grün sehen will |
| 7 | Tote Verbindungen | `feat: Herzschlag und Aufräumen toter Verbindungen` | ein Abbruch darf die Zustellung an andere nicht stören, Voraussetzung für S7 |
| 8 | Verlauf lesen | `feat: Nachrichten nach einer ID aus der Datenbank lesen` | S7 braucht die Quelle, zuerst ohne Stream testbar |
| 9 | Neuverbindung | `feat: Neuverbindung mit Last-Event-ID nachholen` | setzt 7 und 8 voraus, der heikelste Schritt |
| 10 | Compose | `chore: chat-service für Datenbank und Skalierung einrichten` | erst jetzt weiss man, was der Dienst im Stack braucht |
| 11 | Szenarien-Skript | `test: Skript für die Szenarien S2 bis S8 der Live-Zustellung` | prüft den Stack so, wie die Lehrperson prüft |
| 12 | README | `docs: Stand der Live-Zustellung im README nachführen` | zuletzt, weil es den geprüften Stand beschreibt |

---

## Schritt 1: Kommentare im Bestand

**Ziel:** Über jeder Klasse und Methode unter `chat-service/` steht ein Kommentar. Heute fehlen 18,
2 im Hauptcode und 16 in den Tests.

**Warum an dieser Stelle:** Ein reiner Kommentar-Commit bleibt lesbar. Mitten im Feature ginge er
unter.

**Auftrag an die KI:**
> Ergänze in `chat-service/` über jeder Methode ohne Kommentar einen deutschen Kommentar, der sagt,
> warum es sie gibt, nicht was die nächste Zeile tut. Prüfe auch Test-Methoden und die Methoden im
> Test-Doppel `RecordingPublisher`. Ändere keine Logik. Finde die Stellen mit dem Kommentar-Check
> aus `scripts/scenarios.sh`, angewendet auf `chat-service/src`.

**Test:** Der Check meldet 0 Stellen, `mvn -q -pl chat-service -am test` bleibt grün.

**Geprüft und korrigiert:** Diff gelesen: nur 30 neue Kommentarzeilen, keine Zeile gelöscht, also
keine Logik geändert. Stichproben zeigen, dass die Kommentare erklären, wozu die Methode da ist. Alle
14 Tests sind grün. Nichts korrigiert.

---

## Schritt 2: Eigene Queue pro Instanz

**Ziel:** Beim Start legt jede Instanz die Queue `chat.live.<HOSTNAME>` an und bindet sie an
`chat.delivery`.

**Warum an dieser Stelle:** Heute hängt keine Queue am Exchange, jede Kopie geht verloren.

**Auftrag an die KI:**
> Lege in `RabbitConfig` eine nicht dauerhafte, exklusive Queue `chat.live.<instanz>` an und binde sie an den
> Fanout-Exchange `chat.delivery`. Die Instanz-ID kommt aus der Einstellung
> `chat-service.instance-id` in `application.yml`, Wert `${HOSTNAME:local}`. Noch keine Grenzen,
> noch kein Listener. Der Test läuft gegen echten RabbitMQ. Halte dich an `CLAUDE.md`.

**Test:** `LiveQueueIntegrationTest`: Queue existiert unter dem erwarteten Namen; eine über
`MessagePublisher` veröffentlichte Nachricht liegt danach in dieser Queue und in `chat.persist`.

**Geprüft und korrigiert:** Diff gelesen: Die Queue ist nicht dauerhaft und exklusiv, der Name kommt
aus `HOSTNAME` über `chat-service.instance-id`, es gibt noch keinen Listener und keine Grenzen.
Zusätzlich gibt es einen Test, der belegt, dass die Queue exklusiv ist. Die Gegenprobe ohne
`.exclusive()` schlägt fehl. Veraltete Kommentare zu web-gateway und WebSocket sind angepasst. Alle 17
Tests sind grün.

---

## Schritt 3: Stream-Endpunkt

**Ziel:** `GET /stream?roomId=…` antwortet mit `text/event-stream` und hält die Verbindung offen.
Eine Klasse `StreamRegistry` führt die Verbindungen pro Raum und schreibt Nachrichten hinein.

**Warum an dieser Stelle:** Der Endpunkt lässt sich testen, indem der Test die Registry direkt
aufruft. Fehler sind so klar dem Stream zuzuordnen.

**Auftrag an die KI:**
> Baue `GET /stream?roomId=<uuid>` mit `SseEmitter`. Fehlt `roomId` oder ist sie keine UUID, antworte
> mit 400. Sende sofort einen SSE-Kommentar, damit der Client die Header bekommt, ohne auf die erste
> Nachricht zu warten. Baue `StreamRegistry` mit einer Methode, die ein Ereignis an alle Verbindungen
> eines Raums schickt: `id:` ist die Nachrichten-ID, `data:` das JSON als Text. Baue keine eigene
> Sperre um `send`, der Emitter sperrt selbst. Schreibe einen kleinen SSE-Testclient mit
> `java.net.http.HttpClient`, der echte Verbindungen auf einem zufälligen Port öffnet.

**Test:** `StreamControllerIntegrationTest`: Content-Type stimmt; 400 ohne `roomId`; ein Ereignis
über die Registry kommt mit richtigem `id:` und `data:` beim Client an; ein Client in einem anderen
Raum bekommt nichts.

**Geprüft und korrigiert:** `StreamController`, `StreamRegistry` und den Test gelesen: Der Raumfilter
sitzt in `sendToRoom`, der Kommentar `verbunden` geht vor dem Anmelden raus, 400 bei fehlender oder
ungültiger `roomId`. Korrigiert: Beim ersten Lauf hing das Herunterfahren 30 s, weil Spring auf offene
Streams wartet. Darum schliesst die Registry jetzt alle Streams beim Herunterfahren, sonst wäre S4
gefährdet. Verschachtelte Aufrufe im Test aufgelöst. Alle 22 Tests sind grün.

---

## Schritt 4: Queue und Stream verbinden

**Ziel:** Ein `@RabbitListener` auf der Live-Queue dieser Instanz reicht jede Nachricht an die
Registry weiter.

**Warum an dieser Stelle:** Beide Enden sind geprüft, jetzt läuft der ganze Weg.

**Auftrag an die KI:**
> Baue einen Listener auf `chat.live.<instanz>`. Er bekommt die rohe AMQP-Nachricht, liest `id` und
> `roomId` aus dem JSON und gibt den Rumpf unverändert als `data:` weiter, damit das Format genau dem
> in `chat.persist` entspricht. Verlasse dich nicht auf den Header `__TypeId__`. Unlesbare Nachrichten
> werden geloggt und bestätigt, sie dürfen die Zustellung nicht blockieren. Genau ein Consumer pro
> Instanz, damit die Reihenfolge erhalten bleibt.

**Test:** `LiveDeliveryIntegrationTest`: `POST /messages` für Raum A kommt beim Client in Raum A
an, das `data:` enthält alle sechs Felder, der Client in Raum B bekommt nichts.

**Geprüft und korrigiert:** `LiveMessageListener` gelesen: Er nimmt die rohe Nachricht, liest nur `id`
und `roomId` und gibt das JSON unverändert weiter. Eine unlesbare Nachricht wird geloggt und
übersprungen. Genau ein Consumer, damit die Reihenfolge stimmt. Der Test zeigt, dass `data:` gleich dem
JSON auf `chat.persist` ist. Korrigiert: `LiveQueueIntegrationTest` hält jetzt den Listener an, weil er
sonst die Nachricht vor dem Test abholt. Alle 25 Tests sind grün.

---

## Schritt 5: Zwei Instanzen (S3)

**Ziel:** Nachweis, dass zwei Instanzen gegen denselben Broker jede Nachricht an ihre eigenen
Clients bringen.

**Warum an dieser Stelle:** Das ist der Kern der Aufgabe. Hier zeigt sich, ob die Queue pro
Instanz wirklich eigen ist.

**Auftrag an die KI:**
> Schreibe einen Test, der mit `SpringApplicationBuilder` zwei Instanzen des `chat-service` im selben
> Test startet, auf zufälligen Ports, mit verschiedener `chat-service.instance-id` und demselben
> RabbitMQ-Container. An jeder Instanz hängt ein Client auf Raum A, an einer zusätzlich einer auf
> Raum B. Sende über jede Instanz 200 Nachrichten für Raum A, jede Instanz mit eigenem Absender.
> Prüfe: Beide Clients in Raum A haben alle 400, keine doppelt, pro Absender in Sendereihenfolge;
> der Client in Raum B hat keine. Ändere Produktivcode nur, wenn der Test einen echten Fehler
> zeigt, und sag mir dann genau, welchen.

**Test:** `TwoInstancesDeliveryIntegrationTest`, wie im Auftrag.

**Geprüft und korrigiert:** Test gelesen: Er startet zwei Instanzen mit eigener Instanz-ID gegen
denselben RabbitMQ und prüft 400 Ereignisse pro Client in Raum A, ohne doppelte, pro Absender in
Reihenfolge. Raum B bekommt keine Nachricht. Gegenprobe mit gemeinsamer, nicht exklusiver Queue: 200
statt 400, der Test schlägt also fehl, wenn die eigene Queue fehlt. Korrigiert: Die Einstellungen der
Instanzen gehen als Kommandozeilen-Argumente hinein, weil `application.yml` sonst Port und Broker
überschreibt. Alle 26 Tests sind grün.

---

## Schritt 6: Grenzen und Lebensdauer (S4, S6)

**Ziel:** Eine hängende Instanz füllt den Broker nicht, und die Queue einer verschwundenen Instanz
löst sich auf.

**Warum an dieser Stelle:** Grenzen verwerfen Nachrichten. Darum erst, wenn der Normalfall ohne sie
nachweislich läuft.

**Auftrag an die KI:**
> Ergänze die Live-Queue um `x-max-length` 950 und `x-message-ttl` 30000, kein `x-expires`. Setze
> den Prefetch des Listeners auf 50 und erkläre im Kommentar, warum 950 plus 50 die Grenze von 1000
> ergibt: `x-max-length` zählt nur bereitliegende Nachrichten, `rabbitmqctl` zeigt auch unbestätigte.
> Die Queue muss nach einem Verbindungsverlust wieder angelegt werden, mit denselben Argumenten.

**Test:** `LiveQueueLimitsIntegrationTest`: Ein Test-Consumer mit Prefetch 50 bestätigt nie, der Test
publiziert 2000 Nachrichten, bereitliegend plus unbestätigt bleibt höchstens 1000, und die neuesten
sind noch da. Wird die Instanz geschlossen (Spring-Kontext zu, also Verbindung weg), ist ihre Queue
nach höchstens 30 s weg, `chat.persist` und `chat.dlq` bleiben.

**Geprüft und korrigiert:** `RabbitConfig` gelesen: `x-max-length` 950 mit `drop-head`, TTL 30 s, kein
`x-expires`, Prefetch 50; die Begründung für 950 + 50 = 1000 steht im Kommentar. Der Test zeigt 950
bereit + 50 unbestätigt = 1000, und die älteste behaltene Nachricht ist Nr. 1051. Die Queue einer
beendeten Instanz ist innerhalb von 30 s weg, `chat.persist` und `chat.dlq` bleiben. Gegenprobe ohne
`x-max-length`: 1950 statt 950. Korrigiert: Der Test zählt erst nach Publisher Confirms, sonst wäre das
Ergebnis Zufall. Startcode für Testinstanzen in `TestInstances` ausgelagert. Alle 28 Tests sind grün.

---

## Schritt 7: Tote Verbindungen

**Ziel:** Abgebrochene Clients verschwinden aus der Registry. Ein Fehler bei einem Client stört die
anderen nicht.

**Warum an dieser Stelle:** S7 trennt absichtlich. Das muss sauber laufen, bevor das Nachholen dazukommt.

**Auftrag an die KI:**
> Entferne Verbindungen aus `StreamRegistry` bei Abschluss, Timeout und Fehler. Ein Fehler beim
> Schreiben an einen Client darf den Listener nicht abbrechen und andere Clients nicht betreffen.
> Sende alle 15 s einen SSE-Kommentar als Herzschlag, damit tote Verbindungen auffallen.

**Test:** `StreamConnectionCleanupIntegrationTest`: Ein Client trennt, nach dem nächsten Herzschlag
führt die Registry ihn nicht mehr; ein zweiter Client im selben Raum bekommt weiter alle Nachrichten.

**Geprüft und korrigiert:** _nach dem Schritt ausfüllen_

---

## Schritt 8: Verlauf lesen

**Ziel:** Der `chat-service` kann alle Nachrichten eines Raums nach einer bestimmten ID lesen.

**Warum an dieser Stelle:** Eine reine Datenbank-Frage, zuerst getrennt vom heiklen Stream prüfen.

**Auftrag an die KI:**
> Ergänze `spring-boot-starter-jdbc` und den PostgreSQL-Treiber. Baue `MessageHistoryRepository` mit
> zwei Methoden: Die erste sucht eine ID in einem Raum und meldet, wenn es sie dort nicht gibt, damit
> der Stream später mit 409 antworten kann. Die zweite liefert alle Nachrichten des Raums nach dieser
> ID, sortiert nach `sent_at`, dann `id`, aufsteigend. Nur lesen, das Schema gehört dem `batch-writer`:
> keine Migration im `chat-service`. Der Dienst muss ohne erreichbare Datenbank starten und zustellen
> können. Im Test kommt die Tabelle aus einem Skript unter `src/test/resources`.

**Test:** `MessageHistoryRepositoryIntegrationTest`: Reihenfolge stimmt, gleiche `sent_at` werden
sauber aufgelöst, die ID selbst ist nicht dabei, andere Räume auch nicht. Eine unbekannte ID und eine
ID aus einem anderen Raum werden als „nicht gefunden“ gemeldet. Alle bisherigen Tests
laufen weiter ohne Postgres-Container.

**Geprüft und korrigiert:** _nach dem Schritt ausfüllen_

---

## Schritt 9: Neuverbindung (S7)

**Ziel:** Mit `Last-Event-ID` bekommt der Client genau die fehlenden Nachrichten, in Reihenfolge,
keine doppelt, danach wieder live.

**Warum an dieser Stelle:** Hier treffen Datenbank und Live-Weg zusammen, darum zuletzt.

**Auftrag an die KI:**
> Werte den Header `Last-Event-ID` in `GET /stream` aus, so wie es Abschnitt 2.2 und 4.6 der Spec
> festlegen, in dieser Reihenfolge: (1) Ist der Header keine UUID, antworte mit 400. (2) Suche die
> ID in `message`. Fehlt sie oder gehört sie zu einem anderen Raum, antworte mit 409. (3) Registriere
> die Verbindung im Modus „holt nach“: Live-Nachrichten werden gepuffert statt gesendet. (4) Lies
> danach alle Nachrichten des Raums nach der ID, sortiert nach `(sent_at, id)`. Ist die Datenbank in
> (2) oder (4) nicht erreichbar, nimm die Verbindung wieder aus der Registry und antworte mit 503.
> Alles bis hier geschieht vor dem Start des Streams, damit der Client einen Statuscode bekommt.
> (5) Sende die gelesenen Nachrichten und merke ihre IDs. (6) Sende den Puffer, bekannte IDs
> überspringen. (7) Schalte auf live um, so dass zwischen dem Leeren des Puffers und dem Umschalten
> keine Nachricht verloren geht. Kein künstliches Warten. Erkläre mir danach, welche Lücke oder
> Dublette jeder dieser Schritte verhindert.

**Test:** `ReconnectIntegrationTest` mit RabbitMQ und PostgreSQL: Client bekommt eine Nachricht und
trennt; 50 Nachrichten werden publiziert und in die Tabelle geschrieben, wie es der `batch-writer` tut;
Neuverbindung mit `Last-Event-ID`: genau die 50, in Reihenfolge, keine doppelt. Eine danach gesendete
Nachricht kommt live an. Zweiter Fall: Eine Nachricht trifft während des Nachholens ein und kommt genau
einmal an. Fehlerfälle: `Last-Event-ID` keine UUID ergibt 400, unbekannte ID 409, ID aus einem
anderen Raum 409, gestoppter PostgreSQL-Container 503.

**Geprüft und korrigiert:** _nach dem Schritt ausfüllen_

---

## Schritt 10: Compose

**Ziel:** `chat-service` läuft mit Datenbank-Zugang im Stack und lässt sich skalieren.

**Warum an dieser Stelle:** Erst jetzt steht fest, was der Dienst braucht.

**Auftrag an die KI:**
> Gib dem Dienst `chat-service` in `docker-compose.yml` `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`
> und `POSTGRES_PASSWORD`. Kein `depends_on` auf postgres, kein `container_name`, kein `ports:`. Prüfe,
> ob `.env.example` neue Variablen braucht, und ergänze sie nur dann. Starte den Stack mit
> `--scale chat-service=2` und zeige mir, dass beide Instanzen verschiedene Queues haben.

**Test:** `docker compose up -d --build --scale chat-service=2`, `rabbitmqctl list_bindings`: genau
zwei Queues an `chat.delivery`, je ein Consumer. `docker compose ps`: kein veröffentlichter Port.

**Geprüft und korrigiert:** _nach dem Schritt ausfüllen_

---

## Schritt 11: Szenarien-Skript

**Ziel:** S2 bis S8 lassen sich mit einem Befehl am Stack nachstellen.

**Warum an dieser Stelle:** Erst der fertige Stack lässt sich so prüfen, wie die Lehrperson prüft.

**Auftrag an die KI:**
> Schreibe `scripts/scenarios-live.sh` nach dem Muster von `scripts/scenarios.sh`, mit den Befehlen aus
> Abschnitt „Abnahmekriterien“ der Spec. Die SSE-Clients laufen als `curl -N` in Containern im Netz
> `chat-net` und verbinden sich einzeln mit jeder Instanz. Prüfe bei S6 zusätzlich beide Fälle: eine
> kurze Pause und eine, die länger dauert als der Heartbeat von RabbitMQ. Sende die 5000 Nachrichten
> einmal nacheinander und einmal parallel, weil ein schneller Absender die Queue der laufenden Instanz
> über die Grenze treiben könnte. Lass alles auf einem frischen Klon laufen und zeige mir die Messwerte.

**Test:** Das Skript selbst, auf einem frischen Klon: alle Szenarien `OK`.

**Geprüft und korrigiert:** _nach dem Schritt ausfüllen_

---

## Schritt 12: README

**Ziel:** Die Tabelle „Stand“ und die Befehle stimmen.

**Warum an dieser Stelle:** Das README beschreibt, was geprüft ist.

**Auftrag an die KI:**
> Ergänze im README die Live-Zustellung in der Tabelle „Was gebaut wird“, den Befehl für das neue
> Skript und die Links auf Spec und Plan. Führe danach `mvn clean test` im Wurzelverzeichnis aus.

**Test:** `mvn clean test`: ein Lauf, alles grün.

**Geprüft und korrigiert:** _nach dem Schritt ausfüllen_

---

## Änderungen am Plan

_Noch keine. Jede Änderung kommt hier als eigener Eintrag: Datum, was sich ändert, Änderungsgrund._
