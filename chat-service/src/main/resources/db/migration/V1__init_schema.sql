-- ===== M321 Chat App - Initiales Schema =====
-- Wird beim Start automatisch von Flyway ausgeführt.

CREATE TABLE IF NOT EXISTS chats (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chats_name_unique UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS chat_members (
    chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chat_id, user_id),
    CONSTRAINT chat_members_user_id_check CHECK (LENGTH(user_id) > 0)
);

CREATE INDEX IF NOT EXISTS idx_chat_members_user_id ON chat_members(user_id);

CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    chat_id BIGINT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT messages_user_id_check CHECK (LENGTH(user_id) > 0),
    CONSTRAINT messages_content_check CHECK (LENGTH(content) > 0)
);

CREATE INDEX IF NOT EXISTS idx_chat_time ON messages(chat_id, created_at);
CREATE INDEX IF NOT EXISTS idx_messages_user_id ON messages(user_id);

CREATE OR REPLACE VIEW chat_stats AS
SELECT
    c.id,
    c.name,
    COUNT(DISTINCT cm.user_id) AS member_count,
    COUNT(DISTINCT m.id) AS message_count,
    MAX(m.created_at) AS last_message_at
FROM chats c
LEFT JOIN chat_members cm ON c.id = cm.chat_id
LEFT JOIN messages m ON c.id = m.chat_id
GROUP BY c.id, c.name;

INSERT INTO chats (name) VALUES
    ('IT3b Klasse'),
    ('Projekt-Team'),
    ('Random')
ON CONFLICT (name) DO NOTHING;
