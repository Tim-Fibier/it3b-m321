# M321 — Chat-App (Klasse IT3b)

Lernprojekt zum Modul M321: Programmierung verteilter Systeme / Microservices. Wir bauen gemeinsam eine Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit docker-compose gestartet werden.

## Für Lernende: So startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3b-m321.git
   cd it3b-m321
   ```
3. Voraussetzungen installieren: Java 21, Maven, Docker Desktop, Git.
4. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in deinem Fork gelöst. Das Original-Repository bleibt die Referenz.

---

## Was gebaut wird

| Baustein | Technologie | Aufgabe |
|----------|-------------|---------|
| **chat-service** | Spring Boot 3, Java 21 | REST-API + WebSocket, liefert Nachrichten live, prüft das Login-Token |
| **batch-service** | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank, speichert Nachrichten gebündelt |
| **gateway** | nginx | Einziger nach aussen offener Port, Reverse Proxy für alle Anfragen |
| **keycloak** | Keycloak | Login (OIDC / OAuth 2.0) |
| **redis** | Redis | Pub/Sub Message Broker zwischen den Services |
| **postgres** | PostgreSQL | Speichert den Chat-Verlauf |
| **web-ui** | HTML, JavaScript, WebSocket-API | Browser-Client |
| **desktop-ui** | JavaFX (optional) | Desktop-Client gegen dieselbe API |

Alles unterhalb des Gateways läuft in einem **internen Docker-Netzwerk** und ist von aussen nicht erreichbar.

---

## Architektur

### Grundkonzept

Nur der **Gateway-Container** ist von außen (localhost) erreichbar. Er stellt die statischen Frontend-Dateien bereit und leitet alle Anfragen (Login, REST, WebSocket) intern an die jeweiligen Dienste weiter.

**Browser und Backend-Dienste kommunizieren niemals direkt miteinander** — alles läuft über den Gateway.

```
Browser
   │
   │ HTTPS
   ▼
┌─────────────────────────────────────┐
│   nginx Gateway + Web-App           │ ← einziger offener Port (localhost)
│                                      │
│  - statische Dateien                │
│  - Login-Flow zu Keycloak           │
│  - WebSocket → Chat-Service         │
└─────────────────────────────────────┘
   │       │       │
   ├──────┼──────┬─┤
   ▼       ▼      ▼  (internes Netzwerk)
┌──────┐ ┌─────┐ ┌──────────┐
│Keycloak│Batch-Service│  Redis    │
│        │              │Pub/Sub    │
└──────┘ └─────────────┘ └──────────┘
         │
         ▼
    ┌─────────────┐
    │ PostgreSQL  │
    │ (Nachrichten)
    └─────────────┘
```

### Datenfluss

1. **Browser lädt das Frontend** über den Gateway (statische HTML/JS).
2. **Login** läuft über den Gateway zu Keycloak (OIDC). Browser erhält ein **JWT-Token**.
3. **WebSocket-Verbindung** wird zum Gateway aufgebaut, das JWT wird mitgeschickt.
4. **Gateway leitet** die WebSocket-Verbindung an eine Chat-Service-Instanz weiter.
5. **Chat Service validiert** das Token gegen Keyloaks JWKS und verarbeitet die Nachricht.
6. **Nachricht wird persistiert** in PostgreSQL.
7. **Über Redis Pub/Sub** wird die Nachricht an alle anderen Chat-Service-Instanzen verteilt.
8. Diese **informieren ihre WebSocket-Clients** von der neuen Nachricht.

---

## Tech Stack

### Backend
- **Java 21**, Spring Boot 3.x
- **PostgreSQL** für Persistierung
- **Redis** für Pub/Sub zwischen Services
- **Keycloak** für OIDC/OAuth 2.0 Identity Management

### Frontend
- **HTML, CSS, JavaScript** (natives, kein Framework nötig)
- **Browser WebSocket API** (kein Socket.io, kein Blazor)
- Statische Dateien über nginx

### Infrastruktur
- **Docker Compose** für lokale Entwicklung
- **nginx** als Reverse Proxy und Gateway
- Internes Docker-Netzwerk, nur Gateway nach aussen

---

## Datenmodell (Entwurf)

### Tabelle `chats`
```sql
CREATE TABLE chats (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Tabelle `chat_members`
```sql
CREATE TABLE chat_members (
    chat_id BIGINT NOT NULL REFERENCES chats(id),
    user_id VARCHAR(255) NOT NULL,
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chat_id, user_id)
);
```

### Tabelle `messages`
```sql
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT NOT NULL REFERENCES chats(id),
    user_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_chat_time (chat_id, created_at)
);
```

---

## Services im Detail

### Chat Service
- **Aufgabe:** Live-Nachrichten via WebSocket, REST-API für Historie
- **Technologie:** Spring Boot, Server-Sent Events (SSE) oder WebSocket
- **Token-Validierung:** JWKS von Keycloak
- **Publish auf Redis:** Jede neue Nachricht
- **Subscribe von Redis:** Nachrichten von anderen Instanzen

### Batch Service
- **Aufgabe:** Gebündelt in die Datenbank schreiben
- **Technologie:** Spring Boot, Hibernate/JPA
- **Subscribe von Redis:** Alle Nachrichten
- **Liest Nachricht, speichert sie persistiert:** Ist der **einzige Schreiber** zur DB

### Gateway (nginx)
- **Aufgabe:** Einziger öffentlicher Port
- **Statische Dateien:** `/index.html`, `/app.js`, `/styles.css`
- **Reverse Proxy:** `/api/*` → Chat Service
- **Reverse Proxy:** `/auth/*` → Keycloak
- **WebSocket Proxy:** `/ws/*` → Chat Service mit `Upgrade` Header

### Keycloak
- **Aufgabe:** OIDC/OAuth 2.0 Identity Provider
- **Endpoints:** `/auth/realms/<realm>/.well-known/openid-configuration`
- **JWKS:** `/auth/realms/<realm>/protocol/openid-connect/certs`
- **Token-Validierung:** Signaturen via JWKS

### Redis
- **Aufgabe:** Pub/Sub zwischen Services
- **Channel:** `chat.messages` — jede neue Nachricht wird hier published
- **Kein Persistence** nötig, DB ist Source of Truth

---

## Offene Punkte & Roadmap

- [ ] Desktop-UI mit JavaFX (optional, Phase 2)
- [ ] Gruppenchat mit Schreibberechtigungen (Phase 2)
- [ ] Typing-Indicator (Phase 2)
- [ ] File Upload (Phase 3)
- [ ] Nachricht editieren/löschen (Phase 3)
- [ ] Authentifizierung über echten Keycloak (oder Test-Instanz)

---

## Dokumente

- **PLANUNG.md** — Stack, Architektur, Datenmodell (du liest das)
- **CLAUDE.md** — Codestil-Regeln für dieses Projekt
- **docs/design/architektur.html** — Grafische Fassung der Architekturdiagramme
- **docs/plan/bootstrap-chat-service.md** — Schritt-für-Schritt-Plan: Chat Service anlegen, Datenbank, Broker anbinden, erste Tests
- **docs/skizze-architektur.heic** — Die Handskizze aus dem Unterricht

---

## Stand

Das Repository enthält im Moment:
- Die Planung und Codestil-Regeln
- Docker Compose Setup
- Stubs für alle Services

Der Code entsteht **Task für Task** entlang des Bootstrap-Plans.
