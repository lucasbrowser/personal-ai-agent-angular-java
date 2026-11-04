package com.jarvis.ai.conversation.repository;

import com.jarvis.ai.conversation.domain.Conversation;
import com.jarvis.ai.conversation.domain.ConversationStatus;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    private static final String COLUMNS = "id,user_id,title,status,created_at,updated_at,last_message_at";

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Conversation conversation) {
        jdbc.update("INSERT INTO jarvis.conversations (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?)",
                conversation.id(), conversation.userId(), conversation.title(), conversation.status().name(),
                Timestamp.from(conversation.createdAt()), Timestamp.from(conversation.updatedAt()),
                timestamp(conversation.lastMessageAt()));
    }

    public Optional<Conversation> findByIdAndUserId(UUID id, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis.conversations WHERE id=? AND user_id=?",
                (rs, row) -> new Conversation(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("title"), ConversationStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        instant(rs.getTimestamp("last_message_at"))), id, userId).stream().findFirst();
    }

    public List<Conversation> findAllByUserId(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis.conversations WHERE user_id=? "
                        + "ORDER BY updated_at DESC, id", (rs, row) -> new Conversation(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("title"), ConversationStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        instant(rs.getTimestamp("last_message_at"))), userId);
    }

    public boolean deleteByIdAndUserId(UUID id, UUID userId) {
        return jdbc.update("DELETE FROM jarvis.conversations WHERE id=? AND user_id=?", id, userId) == 1;
    }

    private static Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
