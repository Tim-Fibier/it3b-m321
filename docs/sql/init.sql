-- ===== M321 Chat App - Datenbank-Initialisierung =====
-- Führe dieses Skript aus nach: docker-compose up postgres
-- psql -h localhost -U chat_user -d chat_db -f init.sql

-- ===== Tabelle: Chats =====
CREATE TABLE IF NOT EXISTS chats (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chats_name_unique UNIQUE (name)
);

COMMENT ON TABLE chats IS 'Speichert Chats (1:1 oder Gruppen)';
COMMENT ON COLUMN chats.id IS 'Eindeutige Chat-ID';
COMMENT ON COLUMN chats.name IS 'Chat-Name (z.B. "IT3b Klasse" oder "Alice & Bob")';
COMMENT ON COLUMN chats.created_at IS 'Zeitstempel der Chat-Erstellung';

-- ===== Tabelle: Chat-Mitglieder =====
CREATE TABLE IF NOT EXISTS chat_members (
    chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chat_id, user_id),
    CONSTRAINT chat_members_user_id_check CHECK (LENGTH(user_id) > 0)
);

COMMENT ON TABLE chat_members IS 'Speichert Mitgliedschaften zwischen Chats und Benutzern';
COMMENT ON COLUMN chat_members.chat_id IS 'Verweis auf Chat';
COMMENT ON COLUMN chat_members.user_id IS 'Eindeutige Benutzer-ID aus Keycloak';
COMMENT ON COLUMN chat_members.joined_at IS 'Zeitstempel des Beitritts';

CREATE INDEX idx_chat_members_user_id ON chat_members(user_id);

-- ===== Tabelle: Nachrichten =====
CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT messages_user_id_check CHECK (LENGTH(user_id) > 0),
    CONSTRAINT messages_content_check CHECK (LENGTH(content) > 0)
);

COMMENT ON TABLE messages IS 'Speichert alle Nachrichten (Source of Truth)';
COMMENT ON COLUMN messages.id IS 'Eindeutige Nachrichten-ID';
COMMENT ON COLUMN messages.chat_id IS 'Verweis auf Chat';
COMMENT ON COLUMN messages.user_id IS 'Benutzer, der die Nachricht gesendet hat';
COMMENT ON COLUMN messages.content IS 'Nachrichteninhalt (Text)';
COMMENT ON COLUMN messages.created_at IS 'Zeitstempel der Nachricht';

-- Indizes für schnelle Abfragen
CREATE INDEX idx_messages_chat_time ON messages(chat_id, created_at DESC);
CREATE INDEX idx_messages_user_id ON messages(user_id);

-- ===== Initiale Testdaten =====
-- Kommentiert aus, wenn nicht gewünscht. Nur für Lokal-Entwicklung.

INSERT INTO chats (name) VALUES 
    ('IT3b Klasse'),
    ('Projekt-Team'),
    ('Random')
ON CONFLICT (name) DO NOTHING;

-- INSERT INTO chat_members (chat_id, user_id) VALUES
--     (1, 'user-123'),
--     (1, 'user-456'),
--     (2, 'user-123'),
--     (3, 'user-789');
--
-- INSERT INTO messages (chat_id, user_id, content) VALUES
--     (1, 'user-123', 'Hallo zusammen!'),
--     (1, 'user-456', 'Hallo! Wie geht es dir?'),
--     (2, 'user-123', 'Projekt läuft gut'),
--     (3, 'user-789', 'Zufall ist wichtig');

-- ===== Berechtigungen =====
-- Stelle sicher, dass der chat_user Berechtigungen hat:

GRANT CONNECT ON DATABASE chat_db TO chat_user;
GRANT USAGE ON SCHEMA public TO chat_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO chat_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO chat_user;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public TO chat_user;

-- ===== Views (Optional) =====

-- View für Chat-Statistiken
CREATE OR REPLACE VIEW chat_stats AS
SELECT 
    c.id,
    c.name,
    COUNT(DISTINCT cm.user_id) AS member_count,
    COUNT(DISTINCT m.id) AS message_count,
    MAX(m.created_at) AS last_message_at,
    COUNT(DISTINCT m.user_id) AS unique_users
FROM chats c
LEFT JOIN chat_members cm ON c.id = cm.chat_id
LEFT JOIN messages m ON c.id = m.chat_id
GROUP BY c.id, c.name;

COMMENT ON VIEW chat_stats IS 'Statistiken für jeden Chat (Mitglieder, Nachrichten, etc.)';

-- ===== Indices für Performance =====

-- Diese sind bereits oben definiert, aber hier zur Dokumentation:
-- idx_messages_chat_time — für schnelle Abfragen aller Nachrichten eines Chats
-- idx_messages_user_id — für schnelle Abfragen aller Nachrichten eines Benutzers
-- idx_chat_members_user_id — für schnelle Abfragen aller Chats eines Benutzers

-- ===== Audit-Trail (Optional) =====

-- Für Produktion: Logging von Änderungen

-- CREATE TABLE audit_log (
--     id BIGSERIAL PRIMARY KEY,
--     table_name VARCHAR(255),
--     operation VARCHAR(10), -- INSERT, UPDATE, DELETE
--     record_id BIGINT,
--     changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
--     changed_by VARCHAR(255),
--     old_values JSONB,
--     new_values JSONB
-- );

-- ===== Fertig =====

-- Zeige alle Tabellen:
-- \dt
-- 
-- Teste die Abfragen:
-- SELECT * FROM chat_stats;
-- SELECT * FROM messages ORDER BY created_at DESC LIMIT 10;
