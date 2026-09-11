# Web-UI

Natives HTML, CSS und JavaScript Frontend ohne externe Framework-Dependencies. Nutzt die Browser WebSocket-API und Fetch-API.

## Warum keine Frameworks?

**Lernziele:**
- WebSocket-Protokoll direkt verstehen
- DOM Manipulation ohne Abstraktion
- Keine Black-Box-Magie
- Kleinere Bundle-Size
- Schneller zu verstehen

**Trade-offs:**
- Mehr Boilerplate-Code
- Fehler leichter zu machen
- Aber: Alles ist explizit und nachvollziehbar

## Struktur

### index.html

- Header mit Login-Button
- Chat-Liste (Sidebar)
- Nachrichtenfenster
- Input-Bereich
- Modale für "Neuer Chat"

### styles.css

- CSS Grid/Flexbox für Layout
- Responsive Design
- Dunkler Gradient-Background
- Card-basierte UI-Komponenten

### app.js

- **State Management:** Zentrales `appState` Objekt
- **Event Handling:** Alle User-Interaktionen
- **WebSocket:** Echtzeit-Kommunikation
- **Fetch API:** REST Calls
- **DOM Rendering:** Funktionsspezifisch

## Entwicklung

### Lokal mit Live-Reload

```bash
npm install -g live-server
cd web-ui
live-server
```

Browser öffnet `http://localhost:8080` mit automatischem Reload bei Änderungen.

### Gegen einen lokalen Backend

```bash
# Terminal 1: Services starten
docker-compose up postgres redis chat-service-1 chat-service-2 keycloak

# Terminal 2: Gateway + Web-UI mit nginx
docker-compose up gateway

# Browser: http://localhost
```

## API-Endpoints

### Chat-Verwaltung

```javascript
// Alle Chats abrufen
GET /api/chats
  Headers: { Authorization: "Bearer <token>" }
  Response: Array<{id, name, createdAt}>

// Neuen Chat erstellen
POST /api/chats
  Headers: { Authorization: "Bearer <token>", Content-Type: "application/json" }
  Body: {name: "Gruppe Xyz"}
  Response: {id, name, createdAt}

// Nachrichten eines Chats
GET /api/chats/:chatId/messages
  Headers: { Authorization: "Bearer <token>" }
  Response: Array<{id, chatId, userId, content, createdAt}>
```

### WebSocket-Format

```javascript
// Client → Server: Authentifizierung
{
  type: "authenticate",
  token: "<jwt>",
  userId: "user-123"
}

// Client → Server: Neue Nachricht
{
  type: "message",
  chatId: 1,
  content: "Hallo!",
  userId: "user-123",
  timestamp: "2026-09-11T10:30:00Z"
}

// Server → Client: Neue Nachricht (broadcast)
{
  type: "message",
  id: 1,
  chatId: 1,
  userId: "user-123",
  content: "Hallo!",
  createdAt: "2026-09-11T10:30:00"
}
```

## State Management

Das `appState` Objekt hält den kompletten Anwendungszustand:

```javascript
const appState = {
    token: null,                    // JWT Token
    userId: null,                   // Eindeutige Nutzer-ID
    userName: null,                 // Anzeigename
    currentChatId: null,            // Aktuell ausgewählter Chat
    chats: [],                      // Array aller Chats
    messages: {},                   // Object mit chatId → messages[]
    ws: null,                       // WebSocket Verbindung
    isConnected: false,             // WebSocket Status
};
```

**Regel:** Alles in `appState`, keine lokalen Variablen.

## Wichtige Funktionen

### initApp()
Initialisiert die App beim Laden. Prüft, ob Token im localStorage vorhanden.

### handleLogin()
Öffnet Keycloak-Login (aktuell: Mock-Token für Lokal-Entwicklung).

### connectWebSocket()
Stellt WebSocket-Verbindung her und setzt up onopen/onmessage/onerror Handler.

### renderMessages()
Rendert alle Nachrichten des aktuellen Chats in den DOM. Mit Auto-Scroll nach unten.

### handleSendMessage()
Sendet eine Nachricht über WebSocket.

## Login (Mock)

Für lokale Entwicklung wird ein Mock-Token generiert. In Produktion würde Keycloak OIDC Flow verwendet:

```javascript
// Aktuell:
const mockToken = generateMockToken(); // Base64 JWT
appState.token = mockToken;
localStorage.setItem('token', appState.token);

// Produktions-Variant mit Keycloak:
// 1. Redirect zu Keycloak /realms/master/protocol/openid-connect/auth
// 2. Keycloak redirected zurück zu /callback mit Code
// 3. Frontend tauscht Code gegen Token mit /token Endpoint
// 4. Token in localStorage speichern
```

## Security

### XSS-Schutz

Alle Benutzer-Eingaben werden escaped:

```javascript
function escapeHtml(text) {
    const map = {'&': '&amp;', '<': '&lt;', // ...
    return text.replace(/[&<>"']/g, (char) => map[char]);
}
```

### Token-Handling

- Token wird in `localStorage` gespeichert (Browser-Speicher)
- Wird bei jedem API-Call im `Authorization` Header mitgesendet
- Bei Logout wird Token gelöscht

### CORS

Der Gateway handhabt CORS:
- Requests aus `localhost` sind allowed
- In Produktion: nur bestimmte Origins zulassen

## Fehlerbehandlung

```javascript
try {
    const response = await fetch(url, options);
    if (!response.ok) {
        throw new Error('Request fehlgeschlagen: ' + response.status);
    }
    const data = await response.json();
} catch (error) {
    console.error('Fehler:', error);
    showErrorMessage('Etwas ist schiefgelaufen');
}
```

## Browser-Kompatibilität

- Chrome/Edge 60+
- Firefox 55+
- Safari 11+
- Kein IE 11 Support (Fetch API, WebSocket, Promise)

## Tests

Der App.js ist einfach mit Browser DevTools zu testen:

1. Öffne F12 (Konsole)
2. Rufe Funktionen manuell auf:
   ```javascript
   appState
   handleLogin()
   appState.ws.send(JSON.stringify({type: 'message', chatId: 1, content: 'Test'}))
   ```

Oder mit Automated Tests (z. B. Playwright, Cypress):

```bash
npm install cypress
npx cypress open
# Schreibe Tests in cypress/e2e/chat.spec.js
```

## Performance

- **Bundle-Size:** ~10 KB (HTML + CSS + JS)
- **No external dependencies** (außer über CDN, falls genutzt)
- **CSS Grid/Flexbox** für schnelle Rendering
- **Event Delegation** für Nachrichten-Liste

## Nächste Schritte

1. [ ] Typing Indicator (zeige "User tippt...")
2. [ ] Message Search & Filter
3. [ ] Emoji Support
4. [ ] File Upload
5. [ ] Message Edit/Delete
6. [ ] User Presence (wer ist online?)
7. [ ] Dark Mode Toggle
8. [ ] Offline Support (Service Worker)
9. [ ] Notifications (Browser Push API)
10. [ ] Progressive Web App (PWA)
