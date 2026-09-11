# Batch Service

Einziger Schreiber in die PostgreSQL-Datenbank. Abonniert Nachrichten von Redis Pub/Sub und speichert sie gebündelt (in Batches) persistent.

## Architektur

```
Redis Pub/Sub (chat.messages)
      │
      ▼
┌────────────────────┐
│  Batch Service     │
│                    │
├─ Redis Listener    │
├─ Batch Queue       │
├─ Batch Writer      │
└─ JPA Repository    │
      │
      ▼
  PostgreSQL (write)
```

## Warum Batch Service?

- **Konsistentem:** Nur ein Service schreibt, keine Race Conditions
- **Effizienz:** Schreibt mehrere Nachrichten auf einmal (Batch-Operationen)
- **Skalierbarkeit:** Mehrere Chat-Service-Instanzen können lesen, aber nicht schreiben

## Technologie

- Spring Boot 3.3 mit Java 21
- PostgreSQL für Persistierung
- Redis Pub/Sub für Message-Queuing
- Hibernate/JPA für ORM

## Lokale Entwicklung

### Abhängigkeiten starten

```bash
docker-compose up postgres redis
```

### Service starten

```bash
mvn spring-boot:run
```

Der Service läuft auf `http://localhost:8083` (Health-Endpoint nur).

## Konfiguration

### application.yml

```yaml
batch:
  size: 50                 # Nachrichtsge pro Batch
  flush-interval-ms: 5000  # Max. Wartezeit (5 Sekunden)
```

**Logik:**
- Sammelt bis zu 50 Nachrichten, dann schreibe sofort
- ODER warte max. 5 Sekunden, dann schreibe auch wenn nur 10 da

## Redis Listener

Der Service läuft als **Fire-and-Forget** Service:

1. Abonniert Channel `chat.messages`
2. Puffert eingehende Nachrichten
3. Wenn Batch voll ODER Timer abgelaufen → schreibe alle

## Health Check

```bash
curl http://localhost:8083/actuator/health
```

Response:
```json
{
  "status": "UP",
  "components": {
    "redis": {"status": "UP"},
    "db": {"status": "UP"}
  }
}
```

## Monitoring

### Logs anschauen

```bash
mvn spring-boot:run | grep batch
```

Oder in Docker:
```bash
docker logs m321-batch-service -f
```

### Metriken

```bash
curl http://localhost:8083/actuator/metrics
```

Spezifische Metriken:
```bash
curl http://localhost:8083/actuator/metrics/batch.messages.processed
```

## Tests

Unit Tests:

```bash
mvn test
```

Integration Tests (mit Docker):

```bash
mvn verify
```

## Docker Build

```bash
docker build -t m321-batch-service:latest .
```

## Fehlerbehandlung

| Problem | Ursache | Lösung |
|---------|--------|--------|
| `Cannot get a connection` | PostgreSQL nicht erreichbar | `docker-compose up postgres` |
| `No Redis connection` | Redis nicht erreichbar | `docker-compose up redis` |
| `Listener nicht reaktiv` | Redis Pub/Sub nicht verbunden | Logs prüfen: `docker logs m321-batch-service` |
| `Duplicate key error` | Nachricht bereits gespeichert | Idempotenz-Check implementieren |

## Nächste Schritte

1. Idempotenz-Handling (Nachrichten-ID-Duplikate)
2. Retry-Logik für fehlgeschlagene Writes
3. Dead Letter Queue für fehlerhafte Nachrichten
4. Metriken/Monitoring (Prometheus)
5. Partitionierung der Messages-Tabelle (für Skalierung)
