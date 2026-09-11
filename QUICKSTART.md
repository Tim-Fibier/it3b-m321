# Quick Start

Schneller Einstieg in die M321 Chat-App.

## 1. Voraussetzungen checken

```bash
java --version      # Java 21+ erforderlich
mvn --version       # Maven 3.8+ erforderlich
docker --version    # Docker erforderlich
git --version       # Git erforderlich
```

## 2. Projekt klonen und ins Verzeichnis wechseln

```bash
git clone https://github.com/<dein-benutzername>/it3b-m321.git
cd it3b-m321
```

## 3. Alles mit Docker starten

```bash
docker-compose up
```

Beim ersten Mal dauert es länger (Images werden gebaut). Warte, bis alle Container "UP" sind:

```
m321-postgres        |  accepting connections
m321-redis           | Ready to accept connections
m321-chat-service-1  | Started ChatServiceApplication
m321-chat-service-2  | Started ChatServiceApplication
m321-batch-service   | Started BatchServiceApplication
m321-gateway         | [notice] master process started
```

## 4. Browser öffnen

Öffne im Browser: **http://localhost**

Du solltest die Chat-App sehen:
- Button "Mit Keycloak anmelden"
- Klick drauf → du wirst autom. angemeldet (Mock-Token)
- Chat-Fenster öffnet sich

## 5. Chat testen

1. **Neuer Chat:** Klick "+ Neu" → gib Namen ein → "Erstellen"
2. **Nachricht schreiben:** Gib Text in Feld ein → Enter oder "Senden"
3. **Nachrichten sehen:** Sollten sofort angezeigt werden (WebSocket)

## 6. Code entwickeln

Wähle den Service aus, den du entwickeln möchtest:

### Chat Service entwickeln
```bash
# Terminal 1: Dependencies am Laufen halten
docker-compose up postgres redis keycloak

# Terminal 2: Chat Service lokal starten
cd chat-service
mvn spring-boot:run
```

Service läuft auf `http://localhost:8081`.

### Frontend (Web-UI) entwickeln
```bash
# Terminal 1: Dependencies
docker-compose up postgres redis keycloak chat-service-1 chat-service-2

# Terminal 2: nginx Gateway
docker-compose up gateway
```

Browser: `http://localhost`

Oder mit Live-Reload:
```bash
cd web-ui
npm install -g live-server
live-server
```

## 7. Tests schreiben

### Unit Tests für Chat Service
```bash
cd chat-service
mvn test
```

### Code Coverage
```bash
mvn test jacoco:report
# Report: target/site/jacoco/index.html
```

## 8. Logs anschauen

```bash
# Alle Services
docker-compose logs -f

# Nur ein Service
docker-compose logs -f chat-service-1
docker-compose logs -f batch-service
docker-compose logs -f gateway
```

## 9. Datenbank prüfen

```bash
# Verbinde dich mit PostgreSQL
psql -h localhost -U chat_user -d chat_db

# Queries in der Datenbank
\dt                                    # Alle Tabellen
SELECT * FROM chats;                  # Alle Chats
SELECT * FROM messages LIMIT 5;       # Letzte 5 Nachrichten
SELECT * FROM chat_stats;             # Statistiken
```

Oder direkt über Docker:
```bash
docker exec -it m321-postgres psql -U chat_user -d chat_db
```

## 10. Services neu starten

```bash
# Alle neu starten
docker-compose restart

# Einen spezifischen Service
docker-compose restart chat-service-1
docker-compose restart batch-service
docker-compose restart gateway

# Oder mit Rebuild
docker-compose up --build chat-service-1
```

## 11. Alles stoppen

```bash
docker-compose down          # Stoppt alle Container
docker-compose down -v       # Stoppt und löscht Volumes (Datenbank!)
```

## Häufige Fehler

| Fehler | Lösung |
|--------|--------|
| `Port 80 already in use` | Andere App nutzt Port 80. Kill mit `lsof -i :80` oder Port ändern in docker-compose.yml |
| `Connection refused: postgres` | PostgreSQL nicht am Laufen. `docker-compose up postgres` |
| `WebSocket connection failed` | Gateway nicht erreichbar. `docker-compose logs gateway` |
| `404 Not Found` | Frontend-Dateien nicht geladen. Prüfe `gateway/nginx.conf` |
| `JWT validation failed` | Token ungültig. Mock-Token wird automatisch generiert. |

## Code Checklist vor Commit

```bash
# 1. Code folgt Codestil (CLAUDE.md)
# 2. Tests schreiben und grün
mvn test

# 3. Logs prüfen, keine Warnings
docker-compose logs | grep -i error

# 4. Commit mit aussagekräftiger Meldung
git add .
git commit -m "Implementiere WebSocket-Endpoint für Chat-Messages"

# 5. Push zu deinem Fork
git push origin feature/websocket-endpoint
```

## Nächste Schritte

1. **Planung lesen:** `cat PLANUNG.md`
2. **Codestil lernen:** `cat CLAUDE.md`
3. **Bootstrap-Plan folgen:** `cat docs/plan/bootstrap-chat-service.md`
4. **Erste Task implementieren:** REST API für Messages
5. **PR öffnen** gegen dein Fork (für Code Review)

## Hilfe

- **Frage zur Architektur?** → PLANUNG.md
- **Wie schreibe ich Code?** → CLAUDE.md
- **Welche Task kommt nächst?** → docs/plan/bootstrap-*.md
- **Bug oder Problem?** → Schreib ein Issue
- **Stuck?** → Schreib einen Comment auf dem Issue

## Terminal-Tipps

### Überwache Docker Logs
```bash
docker-compose logs -f --tail=50
```

### Health Checks
```bash
curl http://localhost/health                              # Gateway
curl http://localhost/api/chats -H "Authorization: Bearer <token>"  # Chat Service
curl http://localhost:5432 2>&1 | grep -q "PostgreSQL"   # Postgres
redis-cli -h localhost ping                               # Redis
```

### Container-Shell
```bash
docker-compose exec chat-service-1 /bin/bash
docker-compose exec postgres psql -U chat_user -d chat_db
```

---

**Du bist bereit? → Starte jetzt: `docker-compose up`**

Viel Erfolg! 🚀
