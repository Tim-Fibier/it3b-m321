# Chat Service

Live-Chat Service mit WebSocket und REST-API. Speichert Nachrichten über Redis Pub/Sub an Batch Service, validiert JWT-Tokens gegen Keycloak.

## Technologie

- **Spring Boot 3.3** mit Java 21
- **PostgreSQL** für Nachrichten-Verlauf
- **Redis Pub/Sub** für Inter-Service-Kommunikation
- **WebSocket** für Live-Updates an Clients
- **Keycloak** für OAuth 2.0 / OIDC Token-Validierung

## Lokale Entwicklung

### Voraussetzungen

- Java 21
- Maven 3.8+
- Docker (für PostgreSQL und Redis)

### Abhängigkeiten starten

```bash
# In einem Terminal:
docker-compose up postgres redis
```

### Service starten

```bash
mvn spring-boot:run
```

Der Service läuft auf `http://localhost:8081`.

### Gesundheitsprüfung

```bash
curl http://localhost:8081/actuator/health
```

## REST API

### GET /api/chats/:chatId/messages

Gibt alle Nachrichten eines Chats zurück (Verlauf).

**Request:**
```bash
curl -H "Authorization: Bearer <token>" \
  http://localhost:8081/api/chats/1/messages
```

**Response:**
```json
[
  {
    "id": 1,
    "chatId": 1,
    "userId": "user-123",
    "content": "Hallo!",
    "createdAt": "2026-09-11T10:30:00"
  }
]
```

### POST /api/messages

Speichert eine neue Nachricht (wird über WebSocket gesendet).

**Request:**
```json
{
  "chatId": 1,
  "userId": "user-123",
  "content": "Hallo Welt!"
}
```

## WebSocket

Verbindung zu `ws://localhost:8081/ws` (über Gateway: `ws://localhost/ws`).

### Nachrichtenformat

Alle WebSocket-Nachrichten sind JSON:

**Authentifizierung:**
```json
{
  "type": "authenticate",
  "token": "<jwt-token>",
  "userId": "user-123"
}
```

**Neue Nachricht:**
```json
{
  "type": "message",
  "chatId": 1,
  "content": "Hallo Welt!",
  "userId": "user-123",
  "timestamp": "2026-09-11T10:30:00Z"
}
```

**Empfangene Nachricht:**
```json
{
  "type": "message",
  "id": 1,
  "chatId": 1,
  "userId": "user-123",
  "content": "Hallo Welt!",
  "createdAt": "2026-09-11T10:30:00"
}
```

## Tests

Unit Tests:

```bash
mvn test
```

Mit Coverage (Jacoco):

```bash
mvn clean test jacoco:report
# Report: target/site/jacoco/index.html
```

## Docker Build

```bash
docker build -t m321-chat-service:latest .
```

## Environment-Variablen

| Variable | Beschreibung | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | PostgreSQL-Verbindung | `jdbc:postgresql://postgres:5432/chat_db` |
| `SPRING_DATASOURCE_USERNAME` | DB-Benutzer | `chat_user` |
| `SPRING_DATASOURCE_PASSWORD` | DB-Passwort | `chat_password` |
| `SPRING_REDIS_HOST` | Redis-Host | `redis` |
| `SPRING_REDIS_PORT` | Redis-Port | `6379` |
| `KEYCLOAK_URL` | Keycloak-URL | `http://keycloak:8080` |
| `SERVER_PORT` | Service-Port | `8081` |

## Logging

Log-Level steuern über `application.yml`:

```yaml
logging:
  level:
    ch.bzz.m321: DEBUG  # Mehr Details
```

## Architektur

```
WebSocket Client
     │
     ▼
  Gateway (nginx)
     │
     ▼
┌─────────────────┐
│  Chat Service   │
│                 │
├─ Controllers    │
├─ WebSocket      │
├─ Services       │
└─ Repositories   │
     │     │
     │     ▼
     │   PostgreSQL (read)
     │
     ▼
  Redis Pub/Sub (publish)
     │
     ▼
  Batch Service (subscribe, write)
```

## Debugging

### WebSocket mit Browser DevTools

1. Öffne Browser-Konsole (F12)
2. Gehe zu Network → WS
3. Verbindung zu `ws://localhost/ws` prüfen

### Redis mit CLI

```bash
redis-cli
> SUBSCRIBE chat.messages
> PUBLISH chat.messages "{...}"
```

### Logs in Docker

```bash
docker logs m321-chat-service-1 -f
```

## Nächste Schritte

1. REST-API für Chat-Verwaltung (erstellen, löschen, Mitglieder)
2. Typing Indicator über WebSocket
3. Message-Suche und Filter
4. Authentifizierung über echtem Keycloak
