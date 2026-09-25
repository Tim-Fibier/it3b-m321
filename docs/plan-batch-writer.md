# batch-writer — Umsetzungsplan

**Ziel:** Der `batch-writer` holt die Nachrichten aus `chat.persist` und schreibt sie gebündelt,
mit einem ACK nach dem COMMIT, in die Tabelle `message`. Duplikate ergeben eine Zeile, kaputte
Nachrichten gehen nach `chat.dlq`, ein Datenbankausfall verliert nichts.

**Architektur:** Paket pro Aufgabe: `config` richtet beim Start RabbitMQ ein, `message` kennt das
Format der Nachricht, `persistence` spricht mit der Datenbank, `listener` verbindet beides und
entscheidet über ACK, NACK und Reject. Der Listener kennt kein SQL, das Repository kein RabbitMQ.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP, Spring JDBC (`JdbcTemplate`), Flyway,
PostgreSQL 16, RabbitMQ 3.13, JUnit 5, Testcontainers, Maven Multi-Modul.

**Spec:** [`spec-batch-writer.md`](spec-batch-writer.md). Die Abschnittsnummern unten (z. B.
„Spec 3.3“) verweisen dorthin.

## Globale Vorgaben

Für **jede** Aufgabe:

- Regeln aus `CLAUDE.md`: Code englisch, Kommentare, Log-Texte der Doku und Commits deutsch;
  keine verschachtelten Aufrufe; **keine Streams**, stattdessen `for`-Schleifen; **jede Klasse und
  jede Methode** bekommt einen Kommentar, der sagt, warum es sie gibt, auch in den Tests.
- Lombok nur für `@Slf4j` und `@RequiredArgsConstructor`, Datenklassen als `record`.
- Tests heissen `...Test` bzw. `...IntegrationTest`, damit Surefire sie ohne Zusatz-Plugin findet.
- Integrationstests benutzen echte Container: `PostgreSQLContainer("postgres:16-alpine")` und
  `RabbitMQContainer("rabbitmq:3.13-management")`, verbunden mit `@ServiceConnection`.
- Kein `ports:`-Eintrag in `docker-compose.yml`, keine Geheimnisse im Repo.
- Ein Commit pro Aufgabe, Message deutsch im Stil `feat: …` / `chore: …` / `test: …` / `docs: …`,
  mit der Zeile `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Voraussetzung: Docker läuft (Testcontainers).

## Abgrenzung

| Bewusst **nicht** in diesem Plan | Warum |
|---|---|
| Historie lesen, Räume, Keycloak, web-gateway, load-generator | nicht Teil von Bewertung 1 (Spec 1) |
| Fremdschlüssel auf `room` | die Tabelle `room` gibt es noch nicht (Spec 4.1, O1) |
| Werkzeug zum Wiedereinspielen aus `chat.dlq` | Spec O2 |
| Änderungen am `chat-service` | der Vertrag gilt, wie er ist (Spec 2) |

## Dateistruktur

```
pom.xml                                   # Modul batch-writer ergänzen
docker-compose.yml                        # postgres + batch-writer, KEIN ports:-Eintrag
.env.example                              # POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB
scripts/scenarios.sh                      # S2 bis S8 zum Nachstellen
batch-writer/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/ch/benedict/m321/batchwriter/
    │   ├── BatchWriterApplication.java
    │   ├── config/
    │   │   ├── QueueNames.java                   # chat.persist, chat.dlq
    │   │   └── RabbitConfig.java                 # Queues wie im chat-service, Paket-Einstellungen
    │   ├── message/
    │   │   ├── ChatMessage.java                  # eigene Kopie des Vertrags
    │   │   ├── ChatMessageParser.java            # JSON-Rumpf lesen und prüfen
    │   │   └── InvalidMessageException.java      # "diese Nachricht wird nie lesbar"
    │   ├── persistence/
    │   │   └── MessageRepository.java            # insertAll (1 Transaktion), insertOne
    │   └── listener/
    │       └── PersistListener.java              # Paket -> schreiben -> ACK / NACK / Reject
    ├── main/resources/
    │   ├── application.yml
    │   └── db/migration/V1__create_message_table.sql
    └── test/java/ch/benedict/m321/batchwriter/
        ├── BatchWriterApplicationTest.java
        ├── persistence/MessageTableMigrationTest.java
        ├── message/ChatMessageParserTest.java
        ├── persistence/MessageRepositoryIntegrationTest.java
        ├── listener/PersistListenerIntegrationTest.java
        ├── listener/DuplicateMessageIntegrationTest.java
        ├── listener/PoisonMessageIntegrationTest.java
        └── listener/DatabaseOutageIntegrationTest.java
```

**Wer wen kennt:**

```
RabbitMQ ──► PersistListener ──ruft auf──► ChatMessageParser
                   │
                   └──────────ruft auf──► MessageRepository ──► PostgreSQL
```

## Reihenfolge auf einen Blick

| # | Aufgabe | Commit | Warum an dieser Stelle |
|---|---|---|---|
| 1 | Maven-Modul und Anwendungsstart | `chore: Maven-Modul batch-writer anlegen` | ohne startfähiges Modul lässt sich nichts testen |
| 2 | Tabelle `message` mit Flyway | `feat: Tabelle message mit Flyway anlegen` | Repository und Listener brauchen die Tabelle |
| 3 | Nachricht lesen | `feat: Nachrichten aus chat.persist lesen` | reine Logik ohne Container, der Vertrag zuerst |
| 4 | Gebündelt schreiben | `feat: Nachrichten gebündelt in die Datenbank schreiben` | der Kern; erst Datenbank, noch ohne RabbitMQ |
| 5 | Listener, Normalfall | `feat: Pakete aus chat.persist holen und nach dem COMMIT bestätigen` | verbindet 3 und 4, erster Weg Queue → Tabelle |
| 6 | Duplikat über die Queue | `test: Duplikat aus chat.persist ergibt eine Zeile` | S5 prüfen, bevor die Fehlerfälle den Listener verändern |
| 7 | Giftnachrichten | `feat: Giftnachrichten einzeln in die Dead-Letter-Queue legen` | Fehler im Inhalt, bevor Fehler der Umgebung kommen |
| 8 | Datenbankausfall | `feat: bei Datenbankausfall Paket zurücklegen und wiederholen` | braucht die Unterscheidung aus 7 |
| 9 | Docker | `chore: batch-writer und postgres in docker-compose abbilden` | erst jetzt ist der Dienst fertig genug für den Stack |
| 10 | Szenarien-Skript | `test: Skript für die Szenarien S2 bis S8` | prüft den Stack aus 9 so, wie die Lehrperson prüft |
| 11 | README | `docs: Stand von batch-writer und postgres im README nachführen` | zuletzt, weil es den geprüften Stand beschreibt |

---

## Task 1: Maven-Modul und Anwendungsstart

**Dateien:**
- Ändern: `pom.xml` (Modul `batch-writer` ergänzen)
- Anlegen: `batch-writer/pom.xml`
- Anlegen: `batch-writer/src/main/java/ch/benedict/m321/batchwriter/BatchWriterApplication.java`
- Anlegen: `batch-writer/src/main/resources/application.yml`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/BatchWriterApplicationTest.java`

**Schnittstellen:**
- Verbraucht: Eltern-POM `ch.benedict.m321:it3c-m321:0.1.0-SNAPSHOT`
- Stellt bereit: Paketwurzel `ch.benedict.m321.batchwriter`, Artefakt `batch-writer-0.1.0-SNAPSHOT.jar`

- [ ] **Schritt 1: Fehlschlagenden Test schreiben.** `BatchWriterApplicationTest` mit
  `@SpringBootTest`, `@Testcontainers`, je einem statischen `PostgreSQLContainer` und
  `RabbitMQContainer` mit `@ServiceConnection`, und einer leeren Methode `contextLoads()`.
- [ ] **Schritt 2:** `mvn -q -pl batch-writer -am test` → Fehlschlag, das Modul gibt es nicht.
- [ ] **Schritt 3: Modul anlegen.**
  - Eltern-POM: `<module>batch-writer</module>`.
  - `batch-writer/pom.xml`: `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`,
    `postgresql` (runtime), `lombok` (optional); Test: `spring-boot-starter-test`,
    `spring-boot-testcontainers`, `org.testcontainers:junit-jupiter`, `:postgresql`, `:rabbitmq`.
    **Kein** `spring-boot-starter-web`.
  - `BatchWriterApplication` mit `main`.
  - `application.yml`: RabbitMQ wie im `chat-service`, Datenquelle
    `jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/${POSTGRES_DB:chat}?reWriteBatchedInserts=true`,
    Benutzer und Passwort aus `POSTGRES_USER` / `POSTGRES_PASSWORD`,
    `spring.main.keep-alive: true` (ohne Webserver soll die JVM trotzdem weiterlaufen).
- [ ] **Schritt 4:** `mvn -q -pl batch-writer -am test` → grün.
- [ ] **Schritt 5: Committen.** `chore: Maven-Modul batch-writer anlegen`

---

## Task 2: Tabelle `message` mit Flyway

**Dateien:**
- Ändern: `batch-writer/pom.xml` (`flyway-core`, `flyway-database-postgresql`)
- Anlegen: `batch-writer/src/main/resources/db/migration/V1__create_message_table.sql`
- Test: `batch-writer/src/test/java/ch/benedict/m321/batchwriter/persistence/MessageTableMigrationTest.java`

**Schnittstellen:**
- Stellt bereit: Tabelle `message` und Index `idx_message_room_sent_at` genau wie Spec 4.1

- [ ] **Schritt 1: Fehlschlagenden Test schreiben.** Mit `JdbcTemplate` gegen den echten
  Postgres-Container:
  - `SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'message'`
    liefert genau `id uuid`, `room_id uuid`, `sender_id character varying`,
    `sender_name character varying`, `content text`, `sent_at timestamp with time zone`.
  - `SELECT indexname FROM pg_indexes WHERE tablename = 'message'` enthält
    `idx_message_room_sent_at`.
- [ ] **Schritt 2:** `mvn -q -pl batch-writer -am test -Dtest=MessageTableMigrationTest`
  → Fehlschlag, die Tabelle gibt es nicht.
- [ ] **Schritt 3:** Flyway-Abhängigkeiten ergänzen, `V1__create_message_table.sql` mit dem SQL
  aus Spec 4.1.
- [ ] **Schritt 4:** Test grün.
- [ ] **Schritt 5: Committen.** `feat: Tabelle message mit Flyway anlegen`

---

## Task 3: Nachricht lesen

**Dateien:**
- Anlegen: `message/ChatMessage.java`, `message/ChatMessageParser.java`,
  `message/InvalidMessageException.java`
- Test: `message/ChatMessageParserTest.java`

**Schnittstellen:**
- `record ChatMessage(UUID id, UUID roomId, String senderId, String senderName, String content, Instant sentAt)`
- `ChatMessageParser.parse(byte[] body)` → `ChatMessage`; wirft `InvalidMessageException`,
  wenn der Rumpf nach Spec 2.2 unlesbar ist

- [ ] **Schritt 1: Fehlschlagenden Test schreiben.** Reiner Unit-Test ohne Container:
  - die echte Nachricht aus Spec 2.2 (mit Nanosekunden in `sentAt`) wird vollständig gelesen;
  - ein zusätzliches, unbekanntes Feld stört nicht;
  - `"kein JSON"` → `InvalidMessageException`;
  - fehlendes `content` → `InvalidMessageException`;
  - `"roomId": "kein-uuid"` → `InvalidMessageException`.
- [ ] **Schritt 2:** Test → Übersetzungsfehler.
- [ ] **Schritt 3: Umsetzen.** Der Parser baut seinen `ObjectMapper` selbst und sichtbar:
  `JsonMapper.builder().addModule(new JavaTimeModule()).disable(FAIL_ON_UNKNOWN_PROPERTIES).build()`.
  Nach dem Lesen prüft er jedes der sechs Felder auf `null`. Jackson-Fehler werden in
  `InvalidMessageException` übersetzt, damit der Listener nur **eine** Ausnahme kennen muss.
- [ ] **Schritt 4:** Test grün.
- [ ] **Schritt 5: Committen.** `feat: Nachrichten aus chat.persist lesen`

---

## Task 4: Gebündelt schreiben

**Dateien:**
- Anlegen: `persistence/MessageRepository.java`
- Test: `persistence/MessageRepositoryIntegrationTest.java`

**Schnittstellen:**
- `MessageRepository.insertAll(List<ChatMessage> messages)`: ein `batchUpdate` in **einer**
  Transaktion (`@Transactional`)
- `MessageRepository.insertOne(ChatMessage message)`: eine Zeile, eigene Transaktion
- beide mit `ON CONFLICT (id) DO NOTHING`

- [ ] **Schritt 1: Fehlschlagenden Test schreiben** (nur Postgres-Container nötig, RabbitMQ
  läuft für den Kontext mit):
  - 500 Nachrichten mit `insertAll` → 500 Zeilen, Felder stimmen;
  - dieselbe Nachricht zweimal in einer Liste → **1** Zeile (Duplikat im selben `INSERT`);
  - dieselbe Nachricht in zwei Aufrufen → **1** Zeile;
  - `insertOne` mit einem Absendernamen von 300 Zeichen → `DataIntegrityViolationException`.
- [ ] **Schritt 2:** Test → Übersetzungsfehler.
- [ ] **Schritt 3: Umsetzen.** `jdbcTemplate.batchUpdate(INSERT_SQL, messages, messages.size(), setter)`;
  der Setter füllt die sechs Fragezeichen, `sentAt` als `Timestamp.from(...)`.
- [ ] **Schritt 4:** Test grün.
- [ ] **Schritt 5: Committen.** `feat: Nachrichten gebündelt in die Datenbank schreiben`

---

## Task 5: Listener, Normalfall

**Dateien:**
- Anlegen: `config/QueueNames.java`, `config/RabbitConfig.java`, `listener/PersistListener.java`
- Ändern: `application.yml` (`batch-writer.batch-size: 500`, `batch-writer.batch-timeout-ms: 200`)
- Test: `listener/PersistListenerIntegrationTest.java`

**Schnittstellen:**
- `RabbitConfig`: Beans `persistQueue` (durable, `x-dead-letter-exchange ""`,
  `x-dead-letter-routing-key chat.dlq`, identisch zum `chat-service`), `deadLetterQueue`,
  `batchListenerFactory` (Batch-Listener, `consumerBatchEnabled`, `batchSize` 500,
  `receiveTimeout` und `batchReceiveTimeout` 200 ms, `prefetchCount` 500,
  `AcknowledgeMode.MANUAL`, 1 Consumer)
- `PersistListener.onBatch(List<Message> deliveries, Channel channel)`, Listener-ID `persistListener`

- [ ] **Schritt 1: Fehlschlagenden Test schreiben** (Postgres und RabbitMQ):
  - 1000 Nachrichten im Format des `chat-service` in `chat.persist` legen → nach höchstens
    60 s stehen 1000 Zeilen in `message`, `chat.persist` ist leer;
  - **Transaktionen zählen (S4):** Listener über `RabbitListenerEndpointRegistry` anhalten,
    1000 Nachrichten einlegen, `xact_commit` aus `pg_stat_database` merken, Listener starten,
    warten bis alle 1000 da sind → `xact_commit` ist um höchstens 100 gestiegen.
- [ ] **Schritt 2:** Test → Fehlschlag (keine Zeilen).
- [ ] **Schritt 3: Umsetzen.** Pro Paket (Spec 3.1):
  1. jede Zustellung mit `ChatMessageParser` lesen; bei `InvalidMessageException` sofort
     `basicReject(tag, false)` (Spec 3.4);
  2. alle lesbaren mit `insertAll` schreiben;
  3. danach `basicAck(letzteNummer, true)`.
- [ ] **Schritt 4:** Test grün.
- [ ] **Schritt 5: Committen.** `feat: Pakete aus chat.persist holen und nach dem COMMIT bestätigen`

---

## Task 6: Duplikat über die Queue (S5)

**Dateien:**
- Test: `listener/DuplicateMessageIntegrationTest.java`

**Schnittstellen:** keine neuen. Diese Aufgabe beweist, dass Task 4 und 5 zusammen S5 bestehen.

- [ ] **Schritt 1: Test schreiben.** Genau wie die Lehrperson prüft: dieselbe Nachricht, als
  JSON-Text im Format des `chat-service`, **zweimal** über den Standard-Exchange nach
  `chat.persist`, mit `MessageProperties` **nur** `content_type = application/json` (kein
  `__TypeId__`). Erwartet: genau 1 Zeile mit dieser `id`, `chat.dlq` hat 0 Nachrichten,
  `chat.persist` ist leer.
- [ ] **Schritt 2:** Test laufen lassen. Erwartet: **grün**, denn das Verhalten entsteht aus
  `ON CONFLICT` (Task 4) und dem Lesen ohne Header (Task 3). Wird er rot, ist einer der beiden
  falsch, nicht der Test.
- [ ] **Schritt 3: Committen.** `test: Duplikat aus chat.persist ergibt eine Zeile`

---

## Task 7: Giftnachrichten

**Dateien:**
- Ändern: `listener/PersistListener.java`
- Test: `listener/PoisonMessageIntegrationTest.java`

**Schnittstellen:** `PersistListener` behandelt `DataIntegrityViolationException` aus `insertAll`.

- [ ] **Schritt 1: Fehlschlagenden Test schreiben:**
  - `"kein JSON"` in `chat.persist` → landet in `chat.dlq`, Tabelle unverändert
    (klappt schon seit Task 5, sichert Spec 3.4 ab);
  - ein Paket aus zwei guten Nachrichten und einer mit 300 Zeichen `senderName`, gleichzeitig
    eingelegt → die zwei guten stehen in der Tabelle, die kaputte liegt in `chat.dlq`,
    `chat.persist` ist leer.
- [ ] **Schritt 2:** Test → rot: das ganze Paket scheitert, keine der drei wird geschrieben.
- [ ] **Schritt 3: Umsetzen (Spec 3.5).** `catch (DataIntegrityViolationException)` um
  `insertAll`, dann Einzelweg: jede Nachricht mit `insertOne`; klappt es, `basicAck(tag, false)`;
  wieder `DataIntegrityViolationException`, dann `basicReject(tag, false)`.
- [ ] **Schritt 4:** Test grün, alle bisherigen Tests grün.
- [ ] **Schritt 5: Committen.** `feat: Giftnachrichten einzeln in die Dead-Letter-Queue legen`

---

## Task 8: Datenbankausfall (S7)

**Dateien:**
- Ändern: `listener/PersistListener.java`, `application.yml`
  (`batch-writer.retry-pause-ms: 2000`, `spring.datasource.hikari.connection-timeout: 3000`)
- Test: `listener/DatabaseOutageIntegrationTest.java`

**Schnittstellen:** jede andere Ausnahme beim Schreiben → `basicNack(letzteNummer, true, true)`
und Pause; im Einzelweg `basicNack(tag, false, true)`.

- [ ] **Schritt 1: Fehlschlagenden Test schreiben.** Echter Ausfall am echten Postgres, ohne den
  Container zu stoppen (ein neuer Start gäbe einen neuen Port):
  - über eine zweite Verbindung als Superuser auf die Datenbank `postgres`:
    `ALTER DATABASE <db> WITH ALLOW_CONNECTIONS false` und `pg_terminate_backend` für alle
    Verbindungen des batch-writer. Ab jetzt lehnt die Datenbank jede Verbindung ab;
  - 300 Nachrichten einlegen, einige Sekunden warten → `chat.dlq` hat 0 Nachrichten,
    `message` hat 0 neue Zeilen;
  - `ALLOW_CONNECTIONS true` → nach höchstens 60 s stehen alle 300 in `message`,
    `chat.dlq` ist leer. Der Listener läuft noch (`isRunning()`).
- [ ] **Schritt 2:** Test → rot (ohne Behandlung bleiben die Nachrichten unbestätigt hängen).
- [ ] **Schritt 3: Umsetzen (Spec 3.3).** `catch (RuntimeException)` nach dem
  `DataIntegrityViolationException`-Zweig: Paket zurücklegen, `Thread.sleep(retryPauseMs)`.
  Im Test Pause und Verbindungs-Zeitlimit kürzer stellen (`@SpringBootTest(properties = ...)`).
- [ ] **Schritt 4:** Test grün, alle bisherigen Tests grün.
- [ ] **Schritt 5: Committen.** `feat: bei Datenbankausfall Paket zurücklegen und wiederholen`

---

## Task 9: Docker

**Dateien:**
- Anlegen: `batch-writer/Dockerfile` (zweistufig, Kontext = Wurzelverzeichnis wie beim
  `chat-service`)
- Ändern: `docker-compose.yml` (Dienste `postgres` und `batch-writer`, Volume `postgres-data`)
- Ändern: `.env.example` (`POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`)

**Schnittstellen:** Dienste `postgres` und `batch-writer` im Netz `chat-net`, **ohne** `ports:`.

- [ ] **Schritt 1: Prüfung zuerst festlegen:** `docker compose config --services` muss
  `postgres` und `batch-writer` enthalten, `grep -n "ports:" docker-compose.yml` darf nichts
  finden. → heute rot.
- [ ] **Schritt 2: Umsetzen.** `postgres:16-alpine` mit `pg_isready`-Healthcheck;
  `batch-writer` mit `depends_on` auf gesunde `rabbitmq` und `postgres`.
- [ ] **Schritt 3: Prüfen.** `cp .env.example .env && docker compose up -d --build`,
  `docker compose ps` → alles läuft, keine Ports; zehn Nachrichten senden
  (Spec 5, `send 10`) → zehn Zeilen.
- [ ] **Schritt 4: Committen.** `chore: batch-writer und postgres in docker-compose abbilden`

---

## Task 10: Szenarien-Skript

**Dateien:**
- Anlegen: `scripts/scenarios.sh`

**Schnittstellen:** `bash scripts/scenarios.sh` prüft S2 bis S8 am laufenden Stack, in der
Reihenfolge der Lehrperson und ohne Aufräumen dazwischen, und gibt pro Szenario `OK` oder
`FEHLER` mit dem gemessenen Wert aus. S1 bleibt `mvn clean test`.

- [ ] **Schritt 1:** Skript schreiben, mit den Befehlen aus Spec 5.
- [ ] **Schritt 2:** Auf einem **frischen Klon** laufen lassen (`git clone`, `.env` aus
  `.env.example`) → alle acht Szenarien `OK`.
- [ ] **Schritt 3: Committen.** `test: Skript für die Szenarien S2 bis S8`

---

## Task 11: README

**Dateien:**
- Ändern: `README.md` (Tabelle „Was gebaut wird“: `batch-writer` und `postgres` auf
  „vorhanden“, Abschnitt „Bauen, testen, starten“ um den Szenarien-Test ergänzen;
  Verweis auf Spec und Plan)

- [ ] **Schritt 1:** `mvn clean test` im Wurzelverzeichnis → ein Lauf, alles grün.
- [ ] **Schritt 2:** README anpassen.
- [ ] **Schritt 3: Committen.** `docs: Stand von batch-writer und postgres im README nachführen`

---

## Abschluss-Prüfung

- [ ] `mvn clean test` — alle Tests beider Module grün, in einem Lauf (S1)
- [ ] `bash scripts/scenarios.sh` auf einem frischen Klon — S2 bis S8 `OK`
- [ ] `grep -rnE "\.stream\(\)|Stream\.of|Collectors|\.forEach\(" batch-writer/src` — keine Treffer
- [ ] `git ls-files .env` — leer
- [ ] `git log --oneline` — die Commits von Task 1 bis 11 in dieser Reihenfolge, nach Spec und Plan
