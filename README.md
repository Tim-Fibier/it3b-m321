# M321 — Chat-App (Klasse IT3c)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3c-m321.git
   cd it3c-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Lokale Umgebungsdatei anlegen und die Werte anpassen:
   ```bash
   cp .env.example .env
   ```
5. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Bauen, testen, starten

```bash
mvn clean test                   # alle Tests, RabbitMQ und PostgreSQL kommen per Testcontainers
cp .env.example .env             # einmalig: lokale Zugangsdaten
docker compose up -d --build     # RabbitMQ, chat-service, PostgreSQL und batch-writer im Netz chat-net
bash scripts/scenarios.sh        # prüft die Szenarien S2 bis S8 aus Bewertung 1 am laufenden Stack
bash scripts/scenarios-live.sh   # prüft die Szenarien S2 bis S8 der Live-Zustellung (Bewertung 2)
```

Kein Dienst veröffentlicht einen Port auf den Host. Der einzige offene Port des Gesamtsystems
gehört später dem Gateway.

Mehrere `batch-writer` nebeneinander: `docker compose up -d --scale batch-writer=2`. Sie teilen
sich die Queue `chat.persist` (Competing Consumers).

Mehrere `chat-service` nebeneinander: `docker compose up -d --scale chat-service=2`. Hier ist es
umgekehrt: Jede Instanz legt ihre eigene Live-Queue `chat.live.<HOSTNAME>` am Fanout-Exchange
`chat.delivery` an und bekommt so jede Nachricht (Publish/Subscribe). Zurück auf eine Instanz geht
es mit `--scale chat-service=1`; die Queue der beendeten Instanz verschwindet dabei von selbst.

Live mitlesen, von innen aus dem Netz `chat-net` (`<IP>` aus `docker inspect`, `<RAUM>` eine UUID):

```bash
docker run --rm --network chat-net curlimages/curl -sN "http://<IP>:8080/stream?roomId=<RAUM>"
```

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange | vorhanden |
| Live-Zustellung | im chat-service: SSE, RabbitMQ | `GET /stream?roomId=…` liefert die Nachrichten eines Raums als Server-Sent Events. Jede Instanz hat eine eigene, begrenzte Live-Queue (950 + 50 Prefetch, TTL 30 s). Mit `Last-Event-ID` holt ein Client verpasste Nachrichten aus der Tabelle `message` nach | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank: holt `chat.persist` in Paketen (500 Stück oder 200 ms), ein `INSERT` pro Paket, ACK nach dem COMMIT | vorhanden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf in der Tabelle `message`, Schema per Flyway aus dem batch-writer | vorhanden |
| keycloak | Keycloak | Login (OIDC) | folgt |
| web-gateway | nginx | Einziger nach aussen offener Port | folgt |
| Web-UI | React | Browser-Client | folgt |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation des `batch-writer`:
  Vertrag der Queue, Verhalten in jedem Fehlerfall, Datenmodell, Abnahmekriterien.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Umsetzungsplan des `batch-writer`,
  elf Aufgaben, jede mit Test und eigenem Commit.
- [`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md) — Spezifikation der
  Live-Zustellung: Vertrag am Exchange und am Stream, Queues und ihre Grenzen, Verhalten in jedem
  Szenario, Abnahmekriterien.
- [`docs/plan-live-zustellung.md`](docs/plan-live-zustellung.md) — Umsetzungsplan der
  Live-Zustellung, zwölf Schritte, jeder mit Test und eigenem Commit, dazu der Vorversuch.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
