package ch.benedict.m321.batchwriter.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft, dass beim Start genau die Tabelle aus PLANUNG.md 3.7 entsteht.
 *
 * Die Lehrperson prüft die Tabelle message mit diesen Spalten. Stimmt hier
 * ein Name oder ein Typ nicht, schlägt jedes Szenario fehl, das Zeilen
 * zählt. Deshalb wird das Schema selbst getestet, nicht nur das Schreiben.
 */
@SpringBootTest
@Testcontainers
class MessageTableMigrationTest {

    /** Ein leeres PostgreSQL: das Schema muss der batch-writer selbst anlegen. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Wird für den Start des Dienstes gebraucht, hier aber nicht geprüft. */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Die sechs Spalten mit ihren Typen, so wie PostgreSQL sie selbst im
     * information_schema beschreibt.
     */
    @Test
    void createsMessageTableWithAllColumns() {
        String sql = "SELECT column_name, data_type FROM information_schema.columns "
                + "WHERE table_name = 'message'";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        Map<String, String> typeByColumn = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String columnName = (String) row.get("column_name");
            String dataType = (String) row.get("data_type");
            typeByColumn.put(columnName, dataType);
        }

        assertEquals(6, typeByColumn.size());
        assertEquals("uuid", typeByColumn.get("id"));
        assertEquals("uuid", typeByColumn.get("room_id"));
        assertEquals("character varying", typeByColumn.get("sender_id"));
        assertEquals("character varying", typeByColumn.get("sender_name"));
        assertEquals("text", typeByColumn.get("content"));
        assertEquals("timestamp with time zone", typeByColumn.get("sent_at"));
    }

    /** Der Index für die einzige Leseabfrage: die letzten Nachrichten eines Raums. */
    @Test
    void createsIndexForRoomAndTime() {
        String sql = "SELECT indexname FROM pg_indexes WHERE tablename = 'message'";
        List<String> indexNames = jdbcTemplate.queryForList(sql, String.class);

        assertTrue(indexNames.contains("idx_message_room_sent_at"));
    }
}
