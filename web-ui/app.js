/**
 * M321 Chat App - Frontend
 * Verbindet sich mit dem Chat Service über WebSocket und REST API
 * Handhabt Login über Keycloak (OIDC)
 */

// ===== State Management =====

const appState = {
    token: null,
    userId: null,
    userName: null,
    currentChatId: null,
    chats: [],
    messages: {},
    ws: null,
    isConnected: false,
};

// ===== DOM Elements =====

const loginSection = document.getElementById('login-section');
const chatSection = document.getElementById('chat-section');
const loginButton = document.getElementById('login-button');
const logoutButton = document.getElementById('logout-button');
const currentUserEl = document.getElementById('current-user');
const chatList = document.getElementById('chat-list');
const messagesContainer = document.getElementById('messages-container');
const messageInput = document.getElementById('message-input');
const sendButton = document.getElementById('send-button');
const newChatButton = document.getElementById('new-chat-button');
const newChatModal = document.getElementById('new-chat-modal');
const newChatNameInput = document.getElementById('new-chat-name');
const modalCancelBtn = document.getElementById('modal-cancel');
const modalCreateBtn = document.getElementById('modal-create');
const modalCloseBtn = document.querySelector('.modal-close');

// ===== Event Listeners =====

loginButton.addEventListener('click', handleLogin);
logoutButton.addEventListener('click', handleLogout);
sendButton.addEventListener('click', handleSendMessage);
messageInput.addEventListener('keypress', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        handleSendMessage();
    }
});

newChatButton.addEventListener('click', () => {
    newChatModal.classList.remove('hidden');
    newChatNameInput.focus();
});

modalCancelBtn.addEventListener('click', closeNewChatModal);
modalCloseBtn.addEventListener('click', closeNewChatModal);
modalCreateBtn.addEventListener('click', handleCreateChat);

// ===== Login / Logout =====

/**
 * Öffnet den Keycloak-Login-Flow in einem Popup.
 * Nach erfolgreichem Login wird das Token im localStorage gespeichert.
 */
async function handleLogin() {
    try {
        setLoginStatus('Initialisiere Login...', 'info');
        
        // Für lokale Entwicklung: Direkter Token (in echter App: Keycloak OIDC Flow)
        const mockToken = generateMockToken();
        appState.token = mockToken;
        appState.userId = 'user-' + Math.random().toString(36).substr(2, 9);
        appState.userName = 'Benutzer ' + appState.userId.substr(-4);
        
        localStorage.setItem('token', appState.token);
        localStorage.setItem('userId', appState.userId);
        localStorage.setItem('userName', appState.userName);
        
        setLoginStatus('Login erfolgreich!', 'success');
        showChatUI();
        connectWebSocket();
        loadChats();
    } catch (error) {
        setLoginStatus('Login fehlgeschlagen: ' + error.message, 'error');
        console.error('Login-Fehler:', error);
    }
}

/**
 * Abmelden und Aufräumen.
 */
function handleLogout() {
    appState.token = null;
    appState.userId = null;
    appState.userName = null;
    localStorage.removeItem('token');
    localStorage.removeItem('userId');
    localStorage.removeItem('userName');
    
    if (appState.ws) {
        appState.ws.close();
    }
    
    appState.chats = [];
    appState.messages = {};
    appState.currentChatId = null;
    
    showLoginUI();
}

/**
 * Zeigt den Login-Bereich und verbirgt den Chat-Bereich.
 */
function showLoginUI() {
    loginSection.classList.remove('hidden');
    chatSection.classList.add('hidden');
}

/**
 * Zeigt den Chat-Bereich und verbirgt den Login-Bereich.
 */
function showChatUI() {
    loginSection.classList.add('hidden');
    chatSection.classList.remove('hidden');
    currentUserEl.textContent = appState.userName;
}

/**
 * Setzt eine Login-Status-Meldung.
 */
function setLoginStatus(message, type = 'info') {
    const statusEl = document.getElementById('login-status');
    statusEl.textContent = message;
    statusEl.className = 'status-message ' + type;
}

// ===== WebSocket =====

/**
 * Stellt eine WebSocket-Verbindung zum Chat Service her.
 */
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = protocol + '//' + window.location.host + '/ws';
    
    appState.ws = new WebSocket(wsUrl);
    
    appState.ws.onopen = () => {
        appState.isConnected = true;
        console.log('WebSocket verbunden');
        
        // Sende Login-Info
        appState.ws.send(JSON.stringify({
            type: 'authenticate',
            token: appState.token,
            userId: appState.userId,
        }));
    };
    
    appState.ws.onmessage = (event) => {
        handleWebSocketMessage(event.data);
    };
    
    appState.ws.onerror = (error) => {
        console.error('WebSocket-Fehler:', error);
        appState.isConnected = false;
    };
    
    appState.ws.onclose = () => {
        appState.isConnected = false;
        console.log('WebSocket getrennt');
        // Versuche Reconnect nach 3 Sekunden
        setTimeout(connectWebSocket, 3000);
    };
}

/**
 * Handhabt eingehende WebSocket-Nachrichten.
 */
function handleWebSocketMessage(data) {
    try {
        const message = JSON.parse(data);
        
        switch (message.type) {
            case 'message':
                addMessageToChat(message.chatId, message);
                break;
            case 'chat:created':
                loadChats();
                break;
            default:
                console.log('Unbekannte WebSocket-Nachricht:', message);
        }
    } catch (error) {
        console.error('WebSocket-Nachricht Parse-Fehler:', error);
    }
}

/**
 * Sendet eine Nachricht über die WebSocket-Verbindung.
 */
function sendWebSocketMessage(chatId, content) {
    if (!appState.ws || appState.ws.readyState !== WebSocket.OPEN) {
        console.error('WebSocket nicht verbunden');
        return;
    }
    
    appState.ws.send(JSON.stringify({
        type: 'message',
        chatId: chatId,
        content: content,
        userId: appState.userId,
        timestamp: new Date().toISOString(),
    }));
}

// ===== Chat Management =====

/**
 * Lädt die Chat-Liste vom Server.
 */
async function loadChats() {
    try {
        const response = await fetch('/api/chats', {
            headers: {
                'Authorization': 'Bearer ' + appState.token,
            },
        });
        
        if (!response.ok) {
            throw new Error('Chats laden fehlgeschlagen');
        }
        
        const chats = await response.json();
        appState.chats = chats;
        renderChatList();
    } catch (error) {
        console.error('Fehler beim Laden der Chats:', error);
    }
}

/**
 * Erstellt einen neuen Chat.
 */
async function handleCreateChat() {
    const chatName = newChatNameInput.value.trim();
    
    if (!chatName) {
        alert('Bitte gib einen Chat-Namen ein');
        return;
    }
    
    try {
        const response = await fetch('/api/chats', {
            method: 'POST',
            headers: {
                'Authorization': 'Bearer ' + appState.token,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ name: chatName }),
        });
        
        if (!response.ok) {
            throw new Error('Chat-Erstellung fehlgeschlagen');
        }
        
        const newChat = await response.json();
        appState.chats.push(newChat);
        renderChatList();
        selectChat(newChat.id);
        closeNewChatModal();
        newChatNameInput.value = '';
    } catch (error) {
        console.error('Fehler beim Erstellen des Chats:', error);
        alert('Chat konnte nicht erstellt werden');
    }
}

/**
 * Schließt das Modal für neuen Chat.
 */
function closeNewChatModal() {
    newChatModal.classList.add('hidden');
    newChatNameInput.value = '';
}

/**
 * Rendert die Chat-Liste.
 */
function renderChatList() {
    chatList.innerHTML = '';
    
    for (const chat of appState.chats) {
        const li = document.createElement('li');
        li.className = 'chat-item';
        if (chat.id === appState.currentChatId) {
            li.classList.add('active');
        }
        
        const lastMessage = appState.messages[chat.id]?.[appState.messages[chat.id].length - 1];
        const preview = lastMessage ? lastMessage.content.substring(0, 50) : 'Keine Nachrichten';
        
        li.innerHTML = `
            <div class="chat-item-name">${escapeHtml(chat.name)}</div>
            <div class="chat-item-preview">${escapeHtml(preview)}</div>
        `;
        
        li.addEventListener('click', () => selectChat(chat.id));
        chatList.appendChild(li);
    }
}

/**
 * Wählt einen Chat aus.
 */
function selectChat(chatId) {
    appState.currentChatId = chatId;
    messageInput.disabled = false;
    sendButton.disabled = false;
    
    renderChatList();
    renderMessages();
    loadChatHistory(chatId);
}

/**
 * Lädt den Chat-Verlauf vom Server.
 */
async function loadChatHistory(chatId) {
    try {
        const response = await fetch(`/api/chats/${chatId}/messages`, {
            headers: {
                'Authorization': 'Bearer ' + appState.token,
            },
        });
        
        if (!response.ok) {
            throw new Error('Chat-Verlauf konnte nicht geladen werden');
        }
        
        const messages = await response.json();
        if (!appState.messages[chatId]) {
            appState.messages[chatId] = [];
        }
        appState.messages[chatId] = messages;
        renderMessages();
    } catch (error) {
        console.error('Fehler beim Laden des Chat-Verlaufs:', error);
    }
}

// ===== Messages =====

/**
 * Sendet eine neue Nachricht.
 */
function handleSendMessage() {
    const content = messageInput.value.trim();
    
    if (!content || !appState.currentChatId) {
        return;
    }
    
    sendWebSocketMessage(appState.currentChatId, content);
    messageInput.value = '';
    messageInput.focus();
}

/**
 * Fügt eine Nachricht zum Chat hinzu.
 */
function addMessageToChat(chatId, message) {
    if (!appState.messages[chatId]) {
        appState.messages[chatId] = [];
    }
    
    appState.messages[chatId].push(message);
    
    if (appState.currentChatId === chatId) {
        renderMessages();
    }
}

/**
 * Rendert die Nachrichten des aktuellen Chats.
 */
function renderMessages() {
    messagesContainer.innerHTML = '';
    
    const chatId = appState.currentChatId;
    const messages = appState.messages[chatId] || [];
    
    if (messages.length === 0) {
        messagesContainer.innerHTML = '<p class="empty-state">Noch keine Nachrichten in diesem Chat.</p>';
        return;
    }
    
    for (const msg of messages) {
        const messageEl = document.createElement('div');
        const isOwn = msg.userId === appState.userId;
        messageEl.className = 'message ' + (isOwn ? 'own' : 'other');
        
        const timestamp = new Date(msg.createdAt).toLocaleTimeString('de-DE', {
            hour: '2-digit',
            minute: '2-digit',
        });
        
        messageEl.innerHTML = `
            <div class="message-bubble">
                <div>${escapeHtml(msg.content)}</div>
                <div class="message-timestamp">${timestamp}</div>
            </div>
        `;
        
        messagesContainer.appendChild(messageEl);
    }
    
    // Scrolle nach unten
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

// ===== Utility Functions =====

/**
 * Escapt HTML-Zeichen um XSS-Attacken zu verhindern.
 */
function escapeHtml(text) {
    const map = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#039;',
    };
    return text.replace(/[&<>"']/g, (char) => map[char]);
}

/**
 * Generiert ein Mock-JWT-Token für lokale Entwicklung.
 * In Produktion würde dies von Keycloak kommen.
 */
function generateMockToken() {
    const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
    const payload = btoa(JSON.stringify({
        sub: 'user-' + Math.random(),
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 3600,
    }));
    const signature = 'mock-signature';
    return `${header}.${payload}.${signature}`;
}

// ===== Initialization =====

/**
 * Initialisiert die App beim Start.
 * Prüft, ob der Benutzer bereits angemeldet ist.
 */
function initApp() {
    const savedToken = localStorage.getItem('token');
    const savedUserId = localStorage.getItem('userId');
    const savedUserName = localStorage.getItem('userName');
    
    if (savedToken && savedUserId && savedUserName) {
        appState.token = savedToken;
        appState.userId = savedUserId;
        appState.userName = savedUserName;
        showChatUI();
        connectWebSocket();
        loadChats();
    } else {
        showLoginUI();
    }
}

// Starte die App wenn das DOM bereit ist
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initApp);
} else {
    initApp();
}
