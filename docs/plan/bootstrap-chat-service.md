# Bootstrap-Plan: Chat Service

Schritt-für-Schritt-Anleitung zum Aufbau des Chat Services. Jeder Schritt hat einen Test, um sicherzustellen, dass alles funktioniert.

## Ziel

Der Chat Service ist ein Spring Boot 3 Microservice, der:
- REST-API für Nachrichten bereitstellt
- WebSocket-Verbindungen für Live-Updates handhabt
- JWT-Tokens von Keycloak validiert
- Nachrichten über Redis Pub/Sub an andere Instanzen verteilt

## Schritte

### Schritt 1: Projekt-Struktur prüfen

Der Chat Service hat folgende Struktur:

```
chat-service/
├── pom.xml                           # Maven Konfiguration
├── src/main/java/ch/bzz/m321/chatservice/
│   ├── ChatServiceApplication.java   # Main Entry Point
│   ├── controller/                   # REST-API Controller
│   ├── service/                      # Business Logic
│   ├── entity/                       # JPA Entities
│   ├── config/                       # Konfigurationsklassen
│   ├── exception/                    # Custom Exceptions
│   └── dto/                          # Data Transfer Objects
├── src/main/resources/
│   └── application.yml               # Spring Boot Konfiguration
├── src/test/                         # Unit Tests
└── Dockerfile                        # Docker Image
```

**Verifikation:** `mvn clean compile` sollte fehlerfrei durchlaufen.

### Schritt 2: Abhängigkeiten prüfen

Die pom.xml enthält bereits:
- `spring-boot-starter-web` — REST API
- `spring-boot-starter-data-jpa` — Datenbankzugriff
- `spring-boot-starter-data-redis` — Redis Pub/Sub
- `spring-boot-starter-websocket` — WebSocket Support
- `spring-boot-starter-oauth2-resource-server` — JWT Validierung
- `jjwt` — JWT Token Verarbeitung

**Verifikation:**
```bash
mvn dependency:tree | grep -E "(spring-boot|jjwt|postgresql)"
```

### Schritt 3: Datenbank prüfen

PostgreSQL muss laufen mit der Datenbank `chat_db`.

**Verifikation lokal (mit Docker Compose):**
```bash
docker-compose up postgres redis
# In neuem Terminal:
psql -h localhost -U chat_user -d chat_db -c "\dt"
```

Die Tabellen werden durch JPA mit `ddl-auto: validate` erwartet.

### Schritt 4: REST-API für Nachrichten implementieren

**Datei:** `src/main/java/ch/bzz/m321/chatservice/controller/MessageController.java`

Erstelle einen Controller mit folgenden Endpoints:

```java
@RestController
@RequestMapping("/api/messages")
public class MessageController {
    
    /**
     * GET /api/messages?chatId=1 — Alle Nachrichten eines Chats
     */
    @GetMapping
    public List<MessageDTO> getMessages(@RequestParam Long chatId) { ... }
    
    /**
     * POST /api/messages — Eine neue Nachricht speichern (über WebSocket)
     */
    @PostMapping
    public MessageDTO createMessage(@RequestBody MessageDTO dto) { ... }
}
```

**Test:**
```bash
mvn spring-boot:run &
curl -X GET http://localhost:8081/api/messages?chatId=1
```

### Schritt 5: WebSocket-Endpoint implementieren

**Datei:** `src/main/java/ch/bzz/m321/chatservice/config/WebSocketConfig.java`

Konfiguriere WebSocket mit Spring:

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler(), "/ws")
                .setAllowedOrigins("*");
    }
}
```

**Datei:** `src/main/java/ch/bzz/m321/chatservice/handler/ChatWebSocketHandler.java`

Implementiere den WebSocket-Handler:

```java
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    /**
     * Handhabt eingehende WebSocket-Nachrichten.
     * Validiert das JWT-Token und leitet die Nachricht weiter.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) { ... }
}
```

**Test:**
```bash
# Mit wscat oder ähnlichem Tool:
npm install -g wscat
wscat -c ws://localhost:8081/ws
# Sende: {"type":"message","chatId":1,"content":"Hallo"}
```

### Schritt 6: JWT-Token-Validierung

**Datei:** `src/main/java/ch/bzz/m321/chatservice/service/TokenValidationService.java`

Implementiere die Token-Validierung gegen Keycloak:

```java
@Service
public class TokenValidationService {
    
    /**
     * Validiert ein JWT-Token gegen die JWKS des Keycloak-Servers.
     */
    public boolean isTokenValid(String token) { ... }
    
    /**
     * Extrahiert die User-ID aus dem Token.
     */
    public String extractUserId(String token) { ... }
}
```

**Test:**
```bash
# Generiere einen Mock-Token und teste die Validierung
mvn test -Dtest=TokenValidationServiceTest
```

### Schritt 7: Redis Pub/Sub konfigurieren

**Datei:** `src/main/java/ch/bzz/m321/chatservice/config/RedisConfig.java`

Konfiguriere Redis-Connection und Message-Listener:

```java
@Configuration
public class RedisConfig {
    
    @Bean
    public RedisConnectionFactory connectionFactory() { ... }
    
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) { ... }
}
```

**Test:**
```bash
docker-compose up redis
redis-cli
> PUBLISH chat.messages "test message"
```

### Schritt 8: Tests schreiben und ausführen

Schreibe Unit Tests für:
- Token-Validierung
- REST-Endpoints
- WebSocket-Handler
- Redis Pub/Sub

**Ausführung:**
```bash
mvn test
# Oder mit Coverage:
mvn test jacoco:report
```

### Schritt 9: Mit Docker bauen und testen

```bash
docker build -t m321-chat-service:latest .
docker run --rm \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/chat_db \
  -e SPRING_DATASOURCE_USERNAME=chat_user \
  -e SPRING_DATASOURCE_PASSWORD=chat_password \
  -e SPRING_REDIS_HOST=redis \
  -p 8081:8081 \
  m321-chat-service:latest
```

### Schritt 10: Mit docker-compose im vollen System testen

```bash
docker-compose up
# Browser: http://localhost
```

## Checkliste

- [ ] pom.xml mit allen Abhängigkeiten
- [ ] application.yml mit Datenbankverbindung
- [ ] Message und Chat Entities
- [ ] REST Controller für Messages
- [ ] WebSocket-Handler
- [ ] Token-Validierung Service
- [ ] Redis Pub/Sub Listener
- [ ] Unit Tests (mindestens 80% Coverage)
- [ ] Dockerfile und Image-Build
- [ ] docker-compose Test
- [ ] README.md im Service-Verzeichnis

## Fehlerbehandlung

| Problem | Lösung |
|---------|--------|
| `Connection refused: postgres` | Stelle sicher, dass PostgreSQL läuft: `docker-compose up postgres` |
| `JWT validation failed` | Prüfe Keycloak URL und JWKS-Endpoint in `application.yml` |
| `WebSocket connection closed` | Prüfe nginx Gateway-Konfiguration, WebSocket Upgrade Header |
| `Redis Pub/Sub nicht verbunden` | Prüfe Redis ist am Laufen: `docker-compose up redis` |

## Nächste Schritte

1. Batch Service implementieren (speichert Nachrichten persistent)
2. Chat-Verwaltungs-Endpoints (erstellen, löschen, Mitglieder)
3. Desktop-UI mit JavaFX
4. Authentifizierung über echter Keycloak-Instanz
