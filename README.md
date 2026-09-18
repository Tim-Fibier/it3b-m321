# M321 — Chat-App (Klasse IT3b)

Lernprojekt zum Modul M321 Programmierung verteilter Systeme / Microservices. Wir bauen gemeinsam eine Chat-Anwendung aus mehreren Services, die über Redis Pub/Sub miteinander kommunizieren und mit Docker Compose gestartet werden.

## Für Lernende: So startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:

   ```bash
   git clone https://github.com/<dein-benutzername>/it3b-m321.git
   cd it3b-m321
   ```

3. **Voraussetzungen installieren:**
   - Java 21 (JDK)
   - Maven 3.8+
   - Docker Desktop (mit Docker Compose)
   - Git

4. **Die Planung lesen:**
   ```bash
   cat PLANUNG.md
   ```
   Erst verstehen, dann programmieren.

5. **Projekt starten:**

   ```bash
   docker-compose up
   ```

   Browser öffnet sich automatisch auf `http://localhost` (oder öffne es manuell).

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

---

## Was gebaut wird

| Service | Technologie | Aufgabe |
|---------|-------------|---------|
| **chat-service** | Spring Boot 3, Java 21 | REST-API + WebSocket, Live-Nachrichten, Token-Validierung |
| **batch-service** | Spring Boot 3, Java 21 | Einziger Schreiber in die DB, speichert Nachrichten gebündelt |
| **gateway** | nginx | Einziger nach aussen offener Port, Reverse Proxy |
| **keycloak** | Keycloak | Login (OIDC/OAuth 2.0) |
| **redis** | Redis | Pub/Sub Message Broker zwischen Services |
| **postgres** | PostgreSQL | Speichert Chat-Verlauf |
| **web-ui** | HTML, JavaScript | Browser-Client mit WebSocket |
| **desktop-ui** | JavaFX (optional) | Zweiter Client gegen dieselbe API |

**Alle Services unterhalb des Gateways laufen in einem internen Docker-Netzwerk und sind von aussen nicht erreichbar.**

---

## Architektur (kurz)

```
Browser
  │
  │ HTTPS, localhost
  ▼
┌──────────────────┐
│ nginx Gateway    │ ← einziger Port nach aussen
│ + Web-App        │
└──────────────────┘
  │  │  │
  ├──┤  ├────→ Keycloak (Login)
  │  ├──────→ Chat Service (WebSocket, REST)
  │  └──────→ Redis (Pub/Sub)
  │
  └────────→ PostgreSQL (über Batch Service)
```

Siehe **PLANUNG.md** für die vollständige Architektur und den Datenfluss.

---

## Codestil

Massstab: **Kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile. Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream. `if` statt Ternary-Nesting.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode: 1-2 Sätze, was sie tut.
- Kommentare auf Deutsch.

Die vollständigen Regeln stehen in **CLAUDE.md**.

---

## Dokumente

| Datei | Inhalt |
|-------|--------|
| **PLANUNG.md** | Stack, Architektur, Nachrichtenfluss, Datenmodell, Roadmap |
| **CLAUDE.md** | Codestil-Regeln für dieses Projekt |
| **docs/design/2026-08-28-chat-app-architektur.html** | Grafische Fassung (lokal im Browser öffnen) |
| **docs/plan/2026-09-04-chat-service-bootstrap.md** | Schritt-für-Schritt-Plan für den ersten Service |
| **docs/skizze-architektur.heic** | Handskizze aus dem Unterricht |

---

## Struktur des Repositories

```
it3b-m321/
├── README.md                    # dieses File
├── PLANUNG.md                   # Planung, Architektur, Stack
├── CLAUDE.md                    # Codestil-Regeln
├── docker-compose.yml           # alle Services, lokal starten
│
├── chat-service/                # Spring Boot Service für WebSocket + REST-API
│   ├── pom.xml
│   ├── src/main/java/...
│   ├── src/main/resources/...
│   ├── Dockerfile
│   └── README.md                # Service-spezifisch
│
├── batch-service/               # Spring Boot Service für DB-Schreiber
│   ├── pom.xml
│   ├── src/main/java/...
│   ├── src/main/resources/...
│   ├── Dockerfile
│   └── README.md
│
├── gateway/                      # nginx Reverse Proxy
│   ├── nginx.conf
│   ├── Dockerfile
│   └── README.md
│
├── web-ui/                       # Frontend: HTML, CSS, JavaScript
│   ├── index.html
│   ├── styles.css
│   ├── app.js
│   ├── Dockerfile
│   └── README.md
│
├── desktop-ui/                   # JavaFX Desktop-App (optional)
│   ├── pom.xml
│   ├── src/main/java/...
│   └── Dockerfile
│
├── docs/
│   ├── design/
│   │   └── architektur.html      # Architektur-Diagramme
│   ├── plan/
│   │   └── bootstrap-chat-service.md
│   └── skizze-architektur.heic   # Handskizze
│
└── docker-compose.yml            # Alle Services mit einem Befehl starten
```

---

## Lokale Entwicklung

### Voraussetzungen

- **Java 21** — `java --version` sollte 21.x zeigen
- **Maven 3.8+** — `mvn --version`
- **Docker Desktop** — `docker --version` und `docker-compose --version`
- **Git** — `git --version`

### Alle Services starten

```bash
docker-compose up
```

Das baut alle Docker-Images und startet die Container:
- **gateway** auf Port 80 (HTTP) und 443 (HTTPS, selbstsigniert)
- **keycloak** intern auf Port 8080
- **chat-service** intern, mehrere Instanzen
- **batch-service** intern
- **postgres** intern auf Port 5432
- **redis** intern auf Port 6379

Der Browser lädt automatisch `http://localhost`.

### Nur den Chat Service entwickeln (ohne Docker)

Wenn du lokal mit Maven arbeiten willst:

```bash
# PostgreSQL und Redis müssen laufen (z. B. über docker-compose)
docker-compose up postgres redis

# In neuem Terminal:
cd chat-service
mvn spring-boot:run
```

---

## Testen

Jeder Service hat Unit Tests:

```bash
mvn test
```

Oder im Service-Verzeichnis:

```bash
cd chat-service
mvn test
```

---

## Git Workflow

1. **Feature Branch** anlegen für jede Aufgabe:
   ```bash
   git checkout -b feature/add-websocket-endpoint
   ```

2. **Code schreiben** nach **CLAUDE.md** Codestil-Regeln.

3. **Commit** mit aussagekräftiger Meldung (Deutsch, Präsens, imperativ):
   ```bash
   git commit -m "Implementiere WebSocket-Endpoint für Chat-Messages"
   ```

4. **Push** und **Pull Request** gegen dein Fork:
   ```bash
   git push origin feature/add-websocket-endpoint
   ```

---

## Fragen, Probleme, Fehler

- **Frage zur Architektur?** → Schau in **PLANUNG.md**
- **Wie schreibe ich Code?** → **CLAUDE.md**
- **Boot-Plan für den nächsten Service?** → **docs/plan/bootstrap-*.md**
- **Bug oder fehlende Abhängigkeit?** → Öffne ein Issue in diesem Repository

---

## Stand

Das Repository enthält momentan:
- ✅ Planung, Architektur, Codestil-Regeln
- ✅ Docker Compose Setup
- ✅ Verzeichnisstruktur und Stubs
- 🚧 Code entsteht Task für Task

Nächste Schritte:
1. Chat Service Bootstrap (Projekt, Spring Boot, Datenbank, Broker anbinden)
2. REST-API für Nachrichten-Abruf
3. WebSocket für Live-Updates
4. Batch Service für DB-Persistierung
5. Web-Frontend mit JavaScript

---

**Ziel:** Eine verteilte Chat-Anwendung bauen, dabei Microservices, Message Queues, WebSockets und Authentication verstehen.

**Massstab:** Code, den lernende Personen lesen, verstehen und selbst schreiben können.
