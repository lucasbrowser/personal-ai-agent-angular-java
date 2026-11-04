package com.jarvis.ai.conversation.repository;

import com.jarvis.ai.conversation.domain.ConversationMessage;
import com.jarvis.ai.conversation.domain.MessageRole;
import com.jarvis.ai.conversation.domain.MessageSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationMessageRepository {
    private static final String COLUMNS = "id,conversation_id,user_id,role,content,source,external_event_id,created_at";

    private final JdbcTemplate jdbc;

    public ConversationMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean save(ConversationMessage message) {
        int inserted = jdbc.update("INSERT INTO jarvis.conversation_messages (" + COLUMNS + ") "
                        + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (conversation_id,source,external_event_id) "
                        + "WHERE external_event_id IS NOT NULL DO NOTHING",
                message.id(), message.conversationId(), message.userId(), message.role().name(), message.content(),
                message.source().name(), message.externalEventId(), Timestamp.from(message.createdAt()));
        if (inserted == 0) return false;

        Timestamp createdAt = Timestamp.from(message.createdAt());
        jdbc.update("UPDATE jarvis.conversations SET updated_at=GREATEST(updated_at,?), "
                        + "last_message_at=GREATEST(COALESCE(last_message_at,?),?) "
                        + "WHERE id=? AND user_id=?",
                createdAt, createdAt, createdAt, message.conversationId(), message.userId());
        return true;
    }

    public List<ConversationMessage> findAllByConversationAndUserId(UUID conversationId, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis.conversation_messages "
                        + "WHERE conversation_id=? AND user_id=? ORDER BY created_at, id",
                (rs, row) -> new ConversationMessage(
                        rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_id", UUID.class), MessageRole.valueOf(rs.getString("role")),
                        rs.getString("content"), MessageSource.valueOf(rs.getString("source")),
                        rs.getString("external_event_id"), rs.getTimestamp("created_at").toInstant()),
                conversationId, userId);
    }

    public List<ConversationMessage> findPageByConversationAndUserId(
            UUID conversationId, UUID userId, int limit, long offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis.conversation_messages "
                        + "WHERE conversation_id=? AND user_id=? ORDER BY created_at, id LIMIT ? OFFSET ?",
                (rs, row) -> new ConversationMessage(
                        rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_id", UUID.class), MessageRole.valueOf(rs.getString("role")),
                        rs.getString("content"), MessageSource.valueOf(rs.getString("source")),
                        rs.getString("external_event_id"), rs.getTimestamp("created_at").toInstant()),
                conversationId, userId, limit, offset);
    }

    public long countByConversationAndUserId(UUID conversationId, UUID userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.conversation_messages "
                + "WHERE conversation_id=? AND user_id=?", Long.class, conversationId, userId);
        return count == null ? 0 : count;
    }

    public Optional<ConversationMessage> findByConversationSourceAndExternalEventId(
            UUID conversationId, UUID userId, MessageSource source, String externalEventId) {
        if (externalEventId == null) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis.conversation_messages "
                        + "WHERE conversation_id=? AND user_id=? AND source=? AND external_event_id=?",
                (rs, row) -> new ConversationMessage(
                        rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_id", UUID.class), MessageRole.valueOf(rs.getString("role")),
                        rs.getString("content"), MessageSource.valueOf(rs.getString("source")),
                        rs.getString("external_event_id"), rs.getTimestamp("created_at").toInstant()),
                conversationId, userId, source.name(), externalEventId).stream().findFirst();
    }
}
