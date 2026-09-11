# nginx Gateway

Einziger nach aussen offener Port (localhost). Leitet alle Anfragen an die internen Services weiter, speichert statische Frontend-Dateien und handhabt WebSocket-Verbindungen.

## Architektur

```
Browser (localhost:80/443)
     │
     ▼
┌──────────────────────────┐
│   nginx Gateway          │
│                          │
├─ HTTP Routing           │
├─ WebSocket Proxy        │
├─ Static Files (HTML)    │
├─ Load Balancing         │
└─ Security Headers       │
     │  │  │  │
     ├──┤  ├──┤
     │  │  │  │
     ▼  ▼  ▼  ▼
  Chat-1 Chat-2 Keycloak Redis
```

## Ports

- **Port 80:** HTTP (für lokale Entwicklung)
- **Port 443:** HTTPS (mit selbstsigniertem Cert, optional)

## Routing

| Pfad | Ziel | Zweck |
|------|------|-------|
| `/` | `web-ui` | Statische HTML/CSS/JS Dateien |
| `/api/*` | `chat-service` (Load Balanced) | REST API |
| `/ws` | `chat-service` (WebSocket Proxy) | WebSocket für Live-Chat |
| `/auth/*` | `keycloak` | Login und OAuth 2.0 |
| `/health` | Gateway selbst | Gesundheitsprüfung |

## Konfiguration

### nginx.conf

Die Konfiguration definiert:

1. **Upstream für Chat Services:**
   ```nginx
   upstream chat_service {
       least_conn;  # Least connections Load Balancing
       server chat-service-1:8081;
       server chat-service-2:8082;
   }
   ```

2. **Statische Dateien:**
   ```nginx
   location / {
       root /usr/share/nginx/html;
       try_files $uri $uri/ /index.html =404;
   }
   ```

3. **WebSocket Proxy:**
   ```nginx
   location /ws {
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_read_timeout 3600s;
   }
   ```

## Load Balancing

Der Gateway nutzt **Least Connections** zur Verteilung:

```nginx
upstream chat_service {
    least_conn;           # ← weniger verbundene Client zuerst
    server chat-service-1:8081;
    server chat-service-2:8082;
}
```

Wenn eine Instanz ausfällt, leitet der Gateway automatisch um.

## WebSocket Handling

WebSocket-Verbindungen sind speziell:

- **HTTP Upgrade-Header** nötig
- **Connection** Header setzen auf `upgrade`
- **Längere Timeouts** (z. B. 1 Stunde)

```nginx
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 3600s;  # 1 Stunde
```

## Tests lokal

### Gateway ist erreichbar?

```bash
curl http://localhost/health
# Response: healthy
```

### Statische Dateien?

```bash
curl http://localhost/
# Response: HTML Content
```

### WebSocket verbindbar?

```bash
npm install -g wscat
wscat -c ws://localhost/ws
# Verbindung sollte offen bleiben
```

### API über Gateway?

```bash
curl -H "Authorization: Bearer <token>" \
  http://localhost/api/chats
```

## Docker Build

```bash
docker build -t m321-gateway:latest .
```

## Environment-Variablen

Keine — nginx ist stateless und braucht keine Umgebungsvariablen.

## Logging

### Docker Logs

```bash
docker logs m321-gateway -f
```

### Log-Format

```
192.168.1.100 - - [11/Sep/2026 10:30:00] "GET / HTTP/1.1" 200 1234
```

## Security-Überlegungen

### Aktuell (für Lokal-Entwicklung)
- Selbstsigniertes Zertifikat (optional, port 443)
- Alle Origins akzeptiert (`*`)
- Kein CORS-Handling nötig (same-origin)

### Für Produktion
- [ ] TLS/SSL mit echtem Zertifikat
- [ ] CORS-Policy restriktiv
- [ ] Rate Limiting
- [ ] IP-Whitelist (optional)
- [ ] Security Headers (X-Frame-Options, CSP, etc.)

## Fehlerbehandlung

| Problem | Ursache | Lösung |
|---------|--------|--------|
| `Connection refused:8081` | Chat Service läuft nicht | `docker-compose up chat-service-1` |
| `503 Service Unavailable` | Alle Upstreams down | Alle Services prüfen: `docker ps` |
| `WebSocket connection closed` | Proxy Timeout | Timeout in nginx.conf erhöhen |
| `Statische Dateien nicht geladen` | `web-ui` Container fehlt | `docker-compose up gateway` |

## nginx Debugging

### Syntax prüfen

```bash
docker run --rm -v $(pwd)/nginx.conf:/etc/nginx/nginx.conf:ro \
  nginx:1.27 nginx -t
```

### Config neu laden (ohne Downtime)

```bash
docker exec m321-gateway nginx -s reload
```

### Request Tracing

```bash
# In nginx.conf: error_log /var/log/nginx/error.log debug;
docker logs m321-gateway | grep debug
```

## Performance-Tuning

### Worker Processes

```nginx
worker_processes auto;  # Nutze alle CPU Kerne
```

### Connection Buffering

```nginx
client_max_body_size 20M;  # Max. Upload-Größe
```

### Caching

```nginx
expires 24h;  # Browser-Cache für statische Dateien
add_header Cache-Control "public, immutable";
```

## Nächste Schritte

1. TLS/SSL Setup (Let's Encrypt)
2. CORS-Policy konfigurieren
3. Rate Limiting für API
4. Logging zu Prometheus/ELK
5. Canary Deployments für Services
