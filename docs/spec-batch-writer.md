# batch-writer — Spezifikation

**Modul M321 · Bewertung 1 · Stand 25.09.2026**

Grundlage: [`PLANUNG.md`](../PLANUNG.md), Abschnitte 3.4 bis 3.7 und 4.1, sowie der vorhandene
`chat-service`. Wo diese Spezifikation von der Planung abweicht, steht es ausdrücklich da, mit
Begründung (Abschnitt 6).

---

## 1. Zweck und Abgrenzung

### Was der Dienst tut

Der `batch-writer` holt die Nachrichten aus der Queue `chat.persist` und legt sie dauerhaft in
der Tabelle `message` in PostgreSQL ab. Er ist der **einzige Dienst, der in diese Tabelle
schreibt** (PLANUNG.md 3.1).

Er schreibt **gebündelt**: bis zu 500 Nachrichten in einer einzigen Datenbank-Transaktion statt
500 einzelne. Das ist der Grund, warum es ihn gibt. PLANUNG.md 4.1 rechnet mit 1'667 Nachrichten
pro Sekunde. Einzeln wären das 1'667 Transaktionen pro Sekunde, gebündelt etwa 3,3.

### Was der Dienst bewusst nicht tut

| Nicht Aufgabe des batch-writer | Wer oder wann |
|---|---|
| Nachrichten annehmen, ID und Zeitstempel vergeben | `chat-service` (schon vorhanden) |
| Nachrichten an Clients zustellen (`chat.delivery`) | `web-gateway`, später |
| Chat-Historie lesen | `chat-service`, später. Der batch-writer liest nie aus `message` |
| Räume, Mitgliedschaften, Tabellen `room` und `room_member` | später (Schritt 4 ff. der Umsetzungsreihenfolge) |
| Token prüfen, Keycloak | `web-gateway`, später. Der batch-writer hat keine Schnittstelle nach aussen |
| Inhalt prüfen oder verändern (Länge, Wörter, Raum existiert?) | Regeln stehen im `chat-service`. Der batch-writer speichert, was ankommt |
| Nachrichten aus `chat.dlq` wieder einspielen | von Hand, siehe offener Punkt O2 |

Der batch-writer hat **keinen Webserver und keinen Port**. Er spricht nur mit RabbitMQ und
PostgreSQL, beide im Docker-Netz `chat-net`.

---

## 2. Vertrag: was auf der Queue ankommt

### 2.1 Queue

| Eigenschaft | Wert | Beleg |
|---|---|---|
| Name | `chat.persist` | `chat-service/.../config/QueueNames.java`, `PERSIST_QUEUE` |
| Typ | classic, `durable` | `RabbitConfig.persistQueue()`: `QueueBuilder.durable(...)` |
| Argumente | `x-dead-letter-exchange = ""`, `x-dead-letter-routing-key = chat.dlq` | ebenda, und `rabbitmqctl list_queues` (unten) |
| Dead-Letter-Queue | `chat.dlq`, classic, `durable`, ohne Argumente | `RabbitConfig.deadLetterQueue()` |
| Erzeuger | nur der `chat-service` | PLANUNG.md 3.5 |

Nachgemessen am laufenden Stack (25.09.2026, `rabbitmq:3.13-management`):

```
$ docker compose exec rabbitmq rabbitmqctl -q list_queues name type durable arguments
chat.dlq      classic  true  []
chat.persist  classic  true  [{"x-dead-letter-exchange",[]},{"x-dead-letter-routing-key","chat.dlq"}]
```

**Folge für den batch-writer:** Wer eine Nachricht mit `basicReject(tag, requeue = false)`
ablehnt, schickt sie über den Standard-Exchange nach `chat.dlq`. Das ist der einzige Weg
dorthin. Der batch-writer legt die beiden Queues beim Start **mit genau diesen Argumenten**
ebenfalls an (Abschnitt 3.8). Weichen die Argumente ab, lehnt RabbitMQ die Deklaration mit
`PRECONDITION_FAILED` ab.

### 2.2 Nachricht

Eine Nachricht ist ein JSON-Objekt mit sechs Feldern. Quelle ist der Record
`chat-service/.../dto/ChatMessage.java`, den `MessagePublisher.publish()` mit
`convertAndSend(QueueNames.PERSIST_QUEUE, message)` über den Standard-Exchange in die Queue
legt. Der `Jackson2JsonMessageConverter` aus `RabbitConfig` macht daraus JSON.

| Feld | JSON-Typ | Bedeutung | Pflicht |
|---|---|---|---|
| `id` | String, UUID | vom `chat-service` vergeben, weltweit eindeutig | ja |
| `roomId` | String, UUID | Raum der Nachricht | ja |
| `senderId` | String | `sub` des Absenders aus Keycloak | ja |
| `senderName` | String | Anzeigename, denormalisiert | ja |
| `content` | String | Text der Nachricht | ja |
| `sentAt` | String, ISO-8601 in UTC | Server-Zeitpunkt des `chat-service` | ja |

Eine echte Nachricht, mit `POST /messages` erzeugt und mit `rabbitmqadmin get` gelesen:

```json
{
  "payload": "{\"id\":\"ff47108c-0043-489b-a537-f40dc7e67348\",\"roomId\":\"3f2b1c4e-0000-0000-0000-000000000001\",\"senderId\":\"anna\",\"senderName\":\"Anna Muster\",\"content\":\"Hallo Vertrag\",\"sentAt\":\"2026-09-25T08:28:43.509872789Z\"}",
  "properties": {
    "content_encoding": "UTF-8",
    "content_type": "application/json",
    "delivery_mode": 2,
    "headers": { "__TypeId__": "ch.benedict.m321.chatservice.dto.ChatMessage" }
  },
  "redelivered": false,
  "routing_key": "chat.persist"
}
```

Drei Beobachtungen daraus, die das Verhalten bestimmen:

1. **`__TypeId__` ist nicht Teil des Vertrags.** Der Header nennt eine Klasse des
   `chat-service`, die es im batch-writer nicht gibt. In Szenario S5 fehlt er ganz, dort ist nur
   `content_type: application/json` gesetzt. Der batch-writer liest deshalb den **Rumpf** selbst
   als JSON und ignoriert alle Header. Auch ein fehlendes `content_type` stört ihn nicht.
2. **`sentAt` hat Nanosekunden** (`...43.509872789Z`). PostgreSQL speichert in `timestamptz`
   Mikrosekunden. Die Datenbank rundet also auf die sechste Nachkommastelle. Für die Sortierung
   im Chat reicht das.
3. **`delivery_mode: 2`** heisst persistent: die Nachricht überlebt einen Neustart von RabbitMQ,
   weil auch die Queue `durable` ist.

**Zusätzliche Felder** im JSON werden ignoriert (tolerant lesen). So bricht der batch-writer
nicht, wenn der `chat-service` später ein Feld ergänzt.

**Unlesbar** ist eine Nachricht, wenn der Rumpf kein JSON ist, eines der sechs Felder fehlt oder
`null` ist, oder `id`, `roomId` bzw. `sentAt` nicht im erwarteten Format sind.

---

## 3. Verhalten

### 3.1 Normalfall: Paket bilden, schreiben, bestätigen

```
chat.persist ──► Paket (bis 500 Stück oder 200 ms) ──► 1 Transaktion, 1 batchUpdate ──► COMMIT ──► 1 ACK
```

1. Der batch-writer empfängt Nachrichten mit **prefetch 500**: RabbitMQ liefert ihm höchstens
   500 unbestätigte Nachrichten gleichzeitig.
2. Er sammelt sie zu einem **Paket**. Das Paket ist fertig, wenn es **500 Nachrichten** enthält
   **oder** seit seinem Beginn **200 ms** vergangen sind, je nachdem, was zuerst eintritt.
3. Er liest jede Nachricht (Abschnitt 2.2). Unlesbare werden sofort abgelehnt (3.4).
4. Er schreibt alle lesbaren Nachrichten des Pakets in **einer** Transaktion mit **einem**
   `JdbcTemplate.batchUpdate`:
   `INSERT INTO message (...) VALUES (...) ON CONFLICT (id) DO NOTHING`.
   In der JDBC-URL steht `reWriteBatchedInserts=true`. Damit schickt der PostgreSQL-Treiber
   die Zeilen als mehrzeilige `INSERT`s statt einzeln.
5. **Erst nach dem COMMIT** bestätigt er das ganze Paket mit **einem**
   `basicAck(letzteNummer, multiple = true)`.

**Begründung:** Genau so steht es in PLANUNG.md 3.6. Die Paketgrösse senkt die Zahl der
Transaktionen um den Faktor 500 (PLANUNG.md 4.1). Das Zeitlimit sorgt dafür, dass bei wenig
Betrieb eine einzelne Nachricht nicht beliebig lange liegen bleibt. Das ACK nach dem COMMIT
macht die Zustellung **at-least-once**: Stürzt der batch-writer zwischen Empfang und COMMIT
ab, liefert RabbitMQ die unbestätigten Nachrichten erneut, und es geht nichts verloren.

**Wichtig beim Zeitlimit:** Spring AMQP kennt zwei Zeiten. `receiveTimeout` misst nur die
Pause *zwischen* zwei Nachrichten. Kommt alle 100 ms eine Nachricht, würde ein Paket damit erst
bei 500 Stück fertig, also nach 50 Sekunden. Deshalb wird zusätzlich `batchReceiveTimeout`
auf 200 ms gesetzt. Das ist die Zeit ab Beginn des Pakets.

### 3.2 Duplikat (Szenario S5)

Kommt dieselbe Nachricht (gleiche `id`) zweimal an, im selben Paket oder in zwei verschiedenen,
steht danach **genau eine Zeile** in `message`. **Nichts** geht nach `chat.dlq`. Beide
Zustellungen werden bestätigt.

**Begründung:** Duplikate sind bei at-least-once normal, kein Fehler (PLANUNG.md 3.6). `id` ist
Primärschlüssel, und `ON CONFLICT (id) DO NOTHING` überspringt die zweite Zeile, auch innerhalb
desselben `INSERT`. Würde das Duplikat als Fehler behandelt, landete jede erneut zugestellte
Nachricht in der DLQ, obwohl sie längst gespeichert ist.

### 3.3 Datenbank nicht erreichbar (Szenario S7)

Schlägt das Schreiben eines Pakets fehl, weil die Datenbank nicht erreichbar ist (Verbindung
abgebrochen, Container gestoppt, keine neue Verbindung innerhalb von 3 s):

1. **Kein** ACK. Das ganze Paket geht mit `basicNack(letzteNummer, multiple = true, requeue = true)`
   zurück in die Queue.
2. Der batch-writer wartet **2 Sekunden** und nimmt dann das nächste Paket, zuerst wieder
   dieselben Nachrichten.
3. Das wiederholt sich, **ohne Obergrenze**, bis die Datenbank wieder da ist.
4. Nichts davon geht nach `chat.dlq`. Der batch-writer beendet sich nicht und braucht keinen
   Neustart.

**Ergebnis für S7:** Postgres ist 15 s weg. Innerhalb von höchstens 5 s nach der Rückkehr
(3 s Verbindungs-Zeitlimit plus 2 s Pause) wird das nächste Paket geschrieben. Alle 300
Nachrichten stehen danach **in der Tabelle `message`**. In der DLQ steht keine davon.

**Begründung:**

- Eine fehlende Datenbank ist ein Problem der **Umgebung**, nicht der Nachricht. Morgen lässt
  sich dieselbe Nachricht problemlos schreiben. In die DLQ gehört nur, was sich *nie* schreiben
  lässt. Wer Datenbank-Ausfälle in die DLQ zählt, verschiebt beim ersten Neustart von Postgres
  den halben Chat dorthin.
- Die Nachrichten sind in der Queue sicher aufgehoben: sie ist `durable`, die Nachrichten sind
  persistent. Bei einem längeren Ausfall wächst die Queue. Das ist gewollt: Rückstau statt
  Datenverlust.
- Die **Pause** verhindert, dass der Dienst in einer engen Schleife dieselben Nachrichten
  tausendfach pro Sekunde hin- und herschiebt.
- Das kurze **Verbindungs-Zeitlimit** (3 s statt Standard 30 s) sorgt dafür, dass ein Ausfall
  schnell erkannt wird und die Wiederaufnahme schnell folgt.

### 3.4 Unlesbare Nachricht

Kann eine Nachricht nicht gelesen werden (Abschnitt 2.2), lehnt der batch-writer **nur sie**
sofort mit `basicReject(nummer, requeue = false)` ab. RabbitMQ legt sie nach `chat.dlq`. Die
übrigen Nachrichten des Pakets werden normal geschrieben und bestätigt. Im Log steht eine
Warnung mit dem Grund.

**Begründung:** Kaputtes JSON wird auch beim zehnten Versuch nicht lesbar. Zurück in die Queue
gelegt, würde es immer wieder zugestellt und das Paket jedes Mal stören. In der DLQ kann man es
anschauen (Management-Oberfläche oder `rabbitmqadmin get queue=chat.dlq`).

### 3.5 Datenbank lehnt den Inhalt ab (Giftnachricht)

Lehnt PostgreSQL das Paket **wegen des Inhalts** ab, zum Beispiel weil `sender_name` länger als
255 Zeichen ist oder der Text ein Null-Byte enthält, meldet Spring eine
`DataIntegrityViolationException`. Dann gilt:

1. Die Transaktion des Pakets ist zurückgerollt, es steht nichts davon in der Tabelle.
2. Der batch-writer schreibt die Nachrichten des Pakets **einzeln**, jede in ihrer eigenen
   Transaktion.
3. Jede, die einzeln klappt, wird einzeln bestätigt (`basicAck(nummer, false)`).
4. Jede, die einzeln wieder mit `DataIntegrityViolationException` scheitert, wird mit
   `basicReject(nummer, requeue = false)` abgelehnt und landet in `chat.dlq`.
5. Scheitert eine einzeln aus einem **anderen** Grund (zum Beispiel ist die Datenbank genau
   jetzt weg), geht sie mit `basicNack(..., requeue = true)` zurück, und es folgt die Pause
   aus 3.3.

**Begründung:** Ohne den Einzelweg würde **eine** kaputte Nachricht alle 499 anderen im Paket
mitreissen. Der Einzelweg ist langsamer, tritt aber nur im Fehlerfall ein.

### 3.6 batch-writer gestoppt, abgestürzt oder neu gestartet (Szenario S4)

- **Gestoppt, während Nachrichten eintreffen:** Sie sammeln sich in `chat.persist`. Nach dem
  Start holt der batch-writer sie in Paketen zu höchstens 500 ab. 1000 wartende Nachrichten
  ergeben **mindestens 2 Schreib-Transaktionen**. Es können einige mehr werden, wenn RabbitMQ
  beim Start die ersten Nachrichten langsamer liefert, als das Zeitlimit von 200 ms erlaubt.
  Dazu kommen einige wenige Transaktionen, mit denen Flyway beim Start das Schema prüft. Das
  liegt weit unter der Grenze von 100. Der Test `PersistListenerIntegrationTest` misst es.
- **Absturz mitten im Paket:** Unbestätigte Nachrichten liefert RabbitMQ erneut aus, sobald die
  Verbindung weg ist. Wurde das Paket schon committet, aber noch nicht bestätigt, kommen die
  Nachrichten doppelt. Das behandelt 3.2.

### 3.7 Zwei oder mehr Instanzen (Szenario S6)

Alle Instanzen hängen **an derselben Queue** `chat.persist`. RabbitMQ verteilt die Nachrichten
unter ihnen, jede Nachricht geht an genau eine Instanz (**Competing Consumers**, PLANUNG.md 3.5).

Die Instanzen stören sich nicht, weil:

- sie **keinen gemeinsamen Zustand** im Speicher haben. Ein Paket gehört genau einer Instanz;
- jede ihre eigenen Nachrichten bestätigt, über ihren eigenen Kanal;
- auch dieselbe Nachricht, zweimal zugestellt, nur eine Zeile ergibt (`ON CONFLICT`, 3.2);
- das Schema nur einmal angelegt wird: Flyway sperrt die Migration mit einem Datenbank-Lock,
  eine zweite Instanz wartet und findet die Tabelle danach fertig vor.

Jede Instanz arbeitet mit **einem** Empfangs-Thread. Skaliert wird über die Zahl der Instanzen
(`--scale batch-writer=N`), nicht über Threads innerhalb einer Instanz. So bleibt es bei einer
Stelle, an der man Skalierung sieht: `rabbitmqctl list_queues name consumers`.

### 3.8 Start und RabbitMQ

- Beim Start legt der batch-writer `chat.persist` und `chat.dlq` mit denselben Eigenschaften an
  wie der `chat-service`. Existieren sie schon, passiert nichts. **Begründung:** Die
  Startreihenfolge der beiden Dienste ist dann egal. Ohne Deklaration würde der Listener nicht
  starten, wenn der batch-writer vor dem `chat-service` hochkommt.
- Ist RabbitMQ kurz weg, baut Spring AMQP die Verbindung selbst wieder auf. Unbestätigte
  Nachrichten liefert RabbitMQ danach erneut aus (siehe 3.6).
- Der batch-writer startet erst, wenn RabbitMQ und PostgreSQL gesund sind
  (`depends_on: condition: service_healthy`). Beim Start legt Flyway das Schema an (4.1).

### 3.9 Übersicht aller Fälle

| Fall | Erkennung | Reaktion | Wo landet die Nachricht |
|---|---|---|---|
| Normalfall | COMMIT gelingt | 1 ACK für das Paket | `message` |
| Duplikat (S5) | gleiche `id` | `ON CONFLICT DO NOTHING`, ACK | `message`, einmal |
| Unlesbar | JSON-Fehler, Feld fehlt | Reject ohne Requeue | `chat.dlq` |
| Inhalt abgelehnt | `DataIntegrityViolationException` | Einzelweg, nur die kaputte: Reject | gute in `message`, kaputte in `chat.dlq` |
| Datenbank weg (S7) | jede andere Ausnahme beim Schreiben | NACK mit Requeue, 2 s Pause, wiederholen | bleibt in `chat.persist`, danach `message` |
| batch-writer gestoppt (S4) | – | Nachrichten warten in der Queue | nach dem Start `message` |
| Absturz vor ACK | Verbindung weg | RabbitMQ liefert erneut | `message` (evtl. Duplikat, siehe oben) |
| Zwei Instanzen (S6) | – | Competing Consumers | `message`, jede einmal |

---

## 4. Datenmodell und Konfiguration

### 4.1 Tabelle und Index

```sql
CREATE TABLE message (
    id          UUID          PRIMARY KEY,
    room_id     UUID          NOT NULL,
    sender_id   VARCHAR(255)  NOT NULL,
    sender_name VARCHAR(255)  NOT NULL,
    content     TEXT          NOT NULL,
    sent_at     TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_message_room_sent_at ON message (room_id, sent_at DESC);
```

| Spalte | aus JSON-Feld | Entscheid |
|---|---|---|
| `id` | `id` | Primärschlüssel. Kommt vom `chat-service`, nicht von der Datenbank. Nur so erkennt `ON CONFLICT` ein Duplikat |
| `room_id` | `roomId` | **ohne Fremdschlüssel**, siehe unten |
| `sender_id` | `senderId` | 255 reicht für eine Keycloak-`sub` (UUID, 36 Zeichen) |
| `sender_name` | `senderName` | denormalisiert, damit die Historie lesbar bleibt, auch wenn ein Konto gelöscht wird (PLANUNG.md 3.7) |
| `content` | `content` | `TEXT`: die Länge regelt der `chat-service`, nicht die Datenbank |
| `sent_at` | `sentAt` | `TIMESTAMPTZ`: ein Zeitpunkt, unabhängig von der Zeitzone des Servers |

**Index:** `(room_id, sent_at DESC)` passt genau auf die einzige Leseabfrage aus PLANUNG.md 3.7,
„die letzten 50 Nachrichten eines Raums“. Er kostet beim Schreiben etwas, wird aber jetzt
angelegt, weil die Tabelle sonst später unter Last umgebaut werden müsste.

**Kein Fremdschlüssel auf `room`**, anders als im ER-Diagramm von PLANUNG.md 3.7: Die Tabelle
`room` gibt es noch nicht, Räume sind nicht Teil dieser Aufgabe. Und der `chat-service` prüft
heute nicht, ob ein Raum existiert. Mit Fremdschlüssel würde jede Nachricht abgelehnt. Der
Fremdschlüssel kommt mit einer späteren Migration, sobald es Räume gibt (offener Punkt O1).

### 4.2 Wo das Schema entsteht

Das Schema legt der batch-writer **beim Start mit Flyway** an, aus der Datei
`batch-writer/src/main/resources/db/migration/V1__create_message_table.sql`.

Flyway führt beim Start jede SQL-Datei aus `db/migration` aus, die in dieser Datenbank noch nicht
gelaufen ist, und merkt sich das in der Tabelle `flyway_schema_history`.

**Begründung:**

- Der batch-writer ist der einzige Schreiber, also gehört ihm auch das Schema.
- Die **Tests** bekommen dasselbe Schema auf dieselbe Weise: Testcontainers startet ein leeres
  Postgres, der batch-writer startet und Flyway legt die Tabelle an. Es gibt keine zweite
  Kopie des SQL, die veralten kann.
- **Zwei Instanzen**, die gleichzeitig starten, legen die Tabelle nicht doppelt an: Flyway
  sperrt die Migration mit einem Datenbank-Lock.
- Spätere Änderungen, zum Beispiel der Fremdschlüssel, kommen als `V2__...sql` dazu. Die
  bestehenden Daten bleiben erhalten.

Verworfen: ein Init-Skript im Postgres-Container (`docker-entrypoint-initdb.d`). Es läuft nur
beim allerersten Start auf ein leeres Volume, Änderungen erreichen eine bestehende Datenbank
nie, und die Tests bräuchten eine eigene Kopie.

### 4.3 Umgebungsvariablen

Alle Werte kommen aus `.env` (Vorlage `.env.example`, nicht im Repo: `.env`).

| Variable | Beispiel in `.env.example` | Wer liest sie | Wofür |
|---|---|---|---|
| `POSTGRES_USER` | `chat` | `postgres`, `batch-writer` | Datenbank-Benutzer |
| `POSTGRES_PASSWORD` | `bitte-lokal-aendern` | `postgres`, `batch-writer` | Passwort dazu |
| `POSTGRES_DB` | `chat` | `postgres`, `batch-writer` | Name der Datenbank |
| `RABBITMQ_USER` | `chat` | `rabbitmq`, `chat-service`, `batch-writer` | schon vorhanden |
| `RABBITMQ_PASSWORD` | `bitte-lokal-aendern` | `rabbitmq`, `chat-service`, `batch-writer` | schon vorhanden |

Nur in `docker-compose.yml`, nicht in `.env`, weil sie sich aus den Dienstnamen ergeben:

| Variable | Wert im Compose | Vorgabe ohne Docker |
|---|---|---|
| `RABBITMQ_HOST` | `rabbitmq` | `localhost` |
| `POSTGRES_HOST` | `postgres` | `localhost` |

### 4.4 Feste Einstellungen (`application.yml`)

| Einstellung | Wert | Warum |
|---|---|---|
| Paketgrösse (`batch-size`) | 500 | PLANUNG.md 3.6 |
| Zeitlimit pro Paket (`batch-timeout`) | 200 ms | PLANUNG.md 3.6 |
| prefetch | = Paketgrösse | sonst kann nie ein volles Paket zusammenkommen |
| Pause nach Datenbankfehler (`retry-pause`) | 2 s | 3.3 |
| Verbindungs-Zeitlimit zur Datenbank (Hikari `connection-timeout`) | 3 s | 3.3 |
| Bestätigung | manuell | ACK erst nach dem COMMIT (3.1) |
| Empfangs-Threads pro Instanz | 1 | 3.7 |
| Webserver | keiner | kein Port, nichts, was man absichern müsste |

### 4.5 Container

- Dienst `batch-writer` in `docker-compose.yml`, im Netz `chat-net`, **ohne `ports:`**.
- Dienst `postgres` (`postgres:16-alpine`, PLANUNG.md 2.1) im Netz `chat-net`, **ohne `ports:`**,
  mit Volume `postgres-data` und Healthcheck `pg_isready`.
- Eigenes `batch-writer/Dockerfile`, zweistufig wie beim `chat-service`: bauen mit Maven,
  laufen mit einem JRE-Image.
- Maven-Modul `batch-writer` im Eltern-POM, Paket `ch.benedict.m321.batchwriter`.

---

## 5. Abnahmekriterien

Jedes Kriterium ist ein Befehl mit einem erwarteten Ergebnis. Alle Befehle laufen im
Wurzelverzeichnis des Repos. Abgekürzt werden:

```bash
# Anzahl Zeilen in message
ROWS='docker compose exec -T postgres sh -c "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -tAc \"SELECT count(*) FROM message\""'
# Queues mit Nachrichten und Verbrauchern
QUEUES='docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages consumers'
# Committete Transaktionen der Datenbank seit ihrem Start
XACT='docker compose exec -T postgres sh -c "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -tAc \"SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()\""'
# N Nachrichten über POST /messages senden, von innen im Netz chat-net
send() { docker run --rm --network chat-net curlimages/curl sh -c "for i in \$(seq 1 $1); do curl -s -o /dev/null -X POST http://chat-service:8080/messages -H 'Content-Type: application/json' -d '{\"roomId\":\"3f2b1c4e-0000-0000-0000-000000000001\",\"senderId\":\"anna\",\"senderName\":\"Anna Muster\",\"content\":\"Nachricht '\$i'\"}'; done"; }
```

Die fertigen Befehle stehen auch in `scripts/scenarios.sh`, das alle acht Szenarien
nacheinander prüft.

| Nr | Kriterium | Befehl | Erwartet |
|---|---|---|---|
| S1 | Alle Tests grün in einem Lauf | `mvn clean test` | `BUILD SUCCESS`, `Tests run: …, Failures: 0, Errors: 0` |
| S2 | Stack läuft aus frischem Klon, kein Port offen | `cp .env.example .env && docker compose up -d --build`, dann `docker compose ps` | alle Dienste `running`, Spalte `PORTS` ohne `0.0.0.0:` bzw. `->` |
| S3 | 1000 Nachrichten in höchstens 60 s gespeichert | `send 1000`, dann `eval $ROWS` und `eval $QUEUES` | Zeilen um 1000 gestiegen, `chat.persist 0` |
| S4 | Nichts verloren, höchstens 100 Transaktionen | `docker compose stop batch-writer`, `send 1000`, `eval $XACT` merken, `docker compose start batch-writer`, warten bis `chat.persist 0`, `eval $XACT` | Zeilen um 1000 gestiegen, `xact_commit` um höchstens 100 gestiegen |
| S5 | Duplikat ergibt eine Zeile | dieselbe Nachricht zweimal mit `rabbitmqadmin publish routing_key=chat.persist properties='{"content_type":"application/json"}' payload='…'` | genau 1 Zeile mit dieser `id`, `chat.dlq 0` |
| S6 | Zwei Instanzen, nichts doppelt | `docker compose up -d --scale batch-writer=2`, `eval $QUEUES`, `send 1000` | `chat.persist … 2` Verbraucher, Zeilen um 1000 gestiegen, `SELECT count(*) - count(DISTINCT id)` = 0 |
| S7 | Datenbankausfall übersteht der Dienst allein | `docker compose stop postgres`, `send 300`, 15 s warten, `docker compose start postgres`, höchstens 90 s warten | Zeilen um 300 gestiegen, `chat.dlq 0`, `docker compose ps batch-writer` zeigt dieselbe Laufzeit wie vorher (kein Neustart) |
| S8 | Codestil | `grep -rnE "\.stream\(\)\|Stream\.of\|Collectors\|\.forEach\(" batch-writer/src` und `git ls-files .env` | beide ohne Treffer; jede Klasse und Methode unter `batch-writer/src` hat einen Kommentar darüber |

Dazu die Kriterien aus den Tests (`mvn test`, echte Queue und echte Datenbank über
Testcontainers):

| Test | prüft |
|---|---|
| `MessageTableMigrationTest` | Flyway legt `message` mit den sechs Spalten und den Index an |
| `ChatMessageParserTest` | eine echte Nachricht des `chat-service` wird gelesen, kaputtes JSON und fehlende Felder werden erkannt |
| `MessageRepositoryIntegrationTest` | ein Paket in einer Transaktion; Duplikat gibt eine Zeile; zu langer Absendername gibt `DataIntegrityViolationException` |
| `PersistListenerIntegrationTest` | 1000 Nachrichten landen alle in der Tabelle; 1000 wartende Nachrichten brauchen höchstens 100 Transaktionen |
| `DuplicateMessageIntegrationTest` | S5: dieselbe Nachricht zweimal nur mit `content_type` in `chat.persist` gibt eine Zeile, nichts in `chat.dlq` |
| `PoisonMessageIntegrationTest` | kaputtes JSON und eine Giftnachricht landen in `chat.dlq`, die übrigen des Pakets in der Tabelle |
| `DatabaseOutageIntegrationTest` | S7: Datenbank lehnt Verbindungen ab, Nachrichten bleiben in der Queue und nichts in `chat.dlq`; danach sind alle in der Tabelle |

---

## 6. Entscheide und Abweichungen von der Planung

| Nr | Entscheid | Begründung | Verworfen |
|---|---|---|---|
| E1 | JSON-Rumpf selbst lesen, Header ignorieren | `__TypeId__` nennt eine fremde Klasse und fehlt in S5 | `Jackson2JsonMessageConverter` mit Typ-Header; er deserialisiert zudem nur vertrauenswürdige Pakete (siehe Falle 1 in `docs/plan-chat-service.md`) |
| E2 | Paketbildung durch Spring AMQP (`consumerBatchEnabled`, `batchSize`, `batchReceiveTimeout`) | fertig, geprüft, drei Einstellungen statt eigener Pufferklasse mit Timer und Nebenläufigkeit | eigener Puffer mit `ScheduledExecutorService`: mehr Code, Thread-Sicherheit von Hand |
| E3 | Ein Paket, eine Transaktion, ein `batchUpdate` | PLANUNG.md 2.1 und 3.6; S4 misst die Transaktionen | Einzel-INSERTs; ein von Hand zusammengebautes SQL mit 500 `VALUES` |
| E4 | ACK erst nach COMMIT, ein ACK pro Paket | at-least-once, PLANUNG.md 3.6 | ACK beim Empfang: ein Absturz vor dem COMMIT verlöre das Paket |
| E5 | Duplikate über `ON CONFLICT (id) DO NOTHING` | Duplikate sind bei at-least-once erwartet | vorher `SELECT` pro Nachricht: eine Abfrage mehr pro Nachricht und trotzdem nicht sicher bei zwei Instanzen |
| E6 | **In die DLQ nur, was sich nie schreiben lässt, dafür sofort. Datenbankausfälle wiederholen ohne Obergrenze** | siehe unten | „nach 3 fehlgeschlagenen Versuchen“ (PLANUNG.md 3.5) für alle Fehler |
| E7 | Schema per Flyway im batch-writer | Abschnitt 4.2 | Init-Skript im Postgres-Container; `spring.sql.init` (kein Lock bei zwei Instanzen) |
| E8 | kein Fremdschlüssel auf `room` | Tabelle `room` gibt es noch nicht | Tabelle `room` jetzt schon anlegen: nicht Teil der Aufgabe, und der `chat-service` prüft keine Räume |
| E9 | Queues auch im batch-writer deklarieren, mit identischen Argumenten | Startreihenfolge egal | nur der `chat-service` deklariert: batch-writer startet sonst nicht, wenn er zuerst da ist |
| E10 | kein Webserver | kein Port, kein Endpunkt, nichts abzusichern | Actuator für einen Healthcheck: Webserver nur für Docker |

**Zu E6, der Abweichung von PLANUNG.md 3.5.** Die Planung sagt: „Dead Letter, nach 3
fehlgeschlagenen Versuchen“. Das lässt sich mit dieser Queue nicht sauber umsetzen, und es
wäre für einen Teil der Fehler falsch:

- `chat.persist` ist eine **klassische** Queue (Abschnitt 2.1). Sie zählt keine
  Zustellversuche. Es gibt nur das Merkmal `redelivered`, und das ist ja oder nein, keine Zahl.
  Das Argument `x-delivery-limit` gibt es nur bei Quorum-Queues. Die Queue umzustellen hiesse,
  den `chat-service` zu ändern.
- Fehler zerfallen in zwei Arten, und für beide ist „3 Versuche“ die falsche Zahl:
  **Inhaltsfehler** (kaputtes JSON, zu langer Name) scheitern beim dritten Versuch genau wie beim
  ersten, dort sind zwei weitere Versuche verschwendet. **Umgebungsfehler** (Datenbank weg)
  gehen nach drei Versuchen innerhalb weniger Sekunden erst recht in die DLQ, obwohl die
  Nachricht einwandfrei ist. S7 wäre dann nicht bestanden.
- Darum: Inhaltsfehler **sofort** in die DLQ (3.4, 3.5), Umgebungsfehler **ohne Obergrenze**
  wiederholen (3.3). Unterschieden wird an der Ausnahme: `DataIntegrityViolationException` und
  Lesefehler sind Inhaltsfehler, alles andere ist Umgebung.

---

## 7. Offene Punkte

| Nr | Punkt | Möglicher Weg |
|---|---|---|
| O1 | Fremdschlüssel `message.room_id → room.id` fehlt | Migration `V2__...`, sobald es die Tabelle `room` gibt und der `chat-service` Räume prüft |
| O2 | Nachrichten in `chat.dlq` wieder einspielen | heute von Hand (Management-Oberfläche „Move messages“ oder `rabbitmqadmin`). Ein Werkzeug dafür wäre ein eigener Auftrag |
| O3 | Ein dauerhaft kaputtes Schema (z. B. Tabelle gelöscht) wird wie ein Datenbankausfall behandelt | wiederholt endlos und staut die Queue, verliert aber nichts. Sichtbar im Log. Richtig so, solange ein Mensch den Fehler behebt |
| O4 | 500 / 200 ms sind Startwerte | unter Last mit dem späteren `load-generator` messen (PLANUNG.md offener Punkt 7) |
| O5 | Bei langem Datenbankausfall kreisen dieselben 500 Nachrichten alle ~5 s zwischen RabbitMQ und batch-writer | harmlos bei dieser Last. Ausbau: Listener anhalten, bis die Datenbank wieder antwortet |
