-- Kopie von batch-writer/src/main/resources/db/migration/V1__create_message_table.sql.
-- Das Schema gehört dem batch-writer. Der chat-service legt die Tabelle nie selbst
-- an; nur seine Tests brauchen sie in derselben Form, um das Lesen zu prüfen.

CREATE TABLE message (
    -- Vom chat-service vergeben, nicht von der Datenbank. Nur deshalb
    -- erkennt ON CONFLICT (id) eine zweimal zugestellte Nachricht.
    id          UUID          PRIMARY KEY,
    -- Bewusst ohne Fremdschlüssel: die Tabelle room gibt es noch nicht
    -- (Spezifikation 4.1, offener Punkt O1).
    room_id     UUID          NOT NULL,
    -- sub aus Keycloak, eine UUID mit 36 Zeichen
    sender_id   VARCHAR(255)  NOT NULL,
    -- denormalisiert, damit die Historie lesbar bleibt
    sender_name VARCHAR(255)  NOT NULL,
    content     TEXT          NOT NULL,
    -- ein Zeitpunkt, unabhängig von der Zeitzone des Servers
    sent_at     TIMESTAMPTZ   NOT NULL
);

-- Passt genau auf die einzige Leseabfrage: die letzten Nachrichten eines Raums.
CREATE INDEX idx_message_room_sent_at ON message (room_id, sent_at DESC);
