package ch.benedict.m321.batchwriter.persistence;

import ch.benedict.m321.batchwriter.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * Die einzige Stelle im ganzen System, die in die Tabelle message schreibt.
 *
 * Bewusst mit JdbcTemplate statt JPA: batchUpdate ist genau das, was dieser
 * Dienst zeigen soll (PLANUNG.md 2.1). Das SQL steht hier im Klartext.
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * ON CONFLICT (id) DO NOTHING: kommt eine Nachricht zum zweiten Mal an,
     * überspringt die Datenbank die Zeile, statt einen Fehler zu werfen.
     * Duplikate sind bei at-least-once erwartet (Spezifikation 3.2).
     */
    private static final String INSERT_SQL = """
            INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt ein ganzes Paket mit EINEM batchUpdate in EINER Transaktion.
     *
     * @Transactional heisst: Spring öffnet vor der Methode eine Transaktion
     * und macht danach COMMIT, bei einer Ausnahme ROLLBACK. Kehrt die Methode
     * normal zurück, steht das ganze Paket in der Datenbank, und erst dann
     * darf der Listener die Nachrichten bestätigen.
     */
    @Transactional
    public void insertAll(List<ChatMessage> messages) {
        int batchSize = messages.size();
        jdbcTemplate.batchUpdate(INSERT_SQL, messages, batchSize, this::setParameters);

        log.debug("Inserted batch of {} messages in one transaction", batchSize);
    }

    /**
     * Schreibt eine einzelne Nachricht in ihrer eigenen Transaktion.
     *
     * Gebraucht nur im Fehlerfall: lehnt die Datenbank ein Paket wegen des
     * Inhalts ab, findet der Listener damit heraus, welche Nachricht es war
     * (Spezifikation 3.5).
     */
    public void insertOne(ChatMessage message) {
        Timestamp sentAt = Timestamp.from(message.sentAt());
        jdbcTemplate.update(INSERT_SQL,
                message.id(),
                message.roomId(),
                message.senderId(),
                message.senderName(),
                message.content(),
                sentAt);
    }

    /**
     * Füllt die sechs Fragezeichen des INSERT für eine Nachricht.
     * batchUpdate ruft diese Methode einmal pro Nachricht im Paket auf.
     */
    private void setParameters(PreparedStatement statement, ChatMessage message) throws SQLException {
        Timestamp sentAt = Timestamp.from(message.sentAt());
        statement.setObject(1, message.id());
        statement.setObject(2, message.roomId());
        statement.setString(3, message.senderId());
        statement.setString(4, message.senderName());
        statement.setString(5, message.content());
        statement.setTimestamp(6, sentAt);
    }
}
