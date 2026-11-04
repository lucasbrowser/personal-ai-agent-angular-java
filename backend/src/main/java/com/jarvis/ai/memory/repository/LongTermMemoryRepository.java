package com.jarvis.ai.memory.repository;

import com.jarvis.ai.memory.domain.LongTermMemory;
import com.jarvis.ai.memory.domain.MemorySourceType;
import com.jarvis.ai.memory.domain.MemoryStatus;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LongTermMemoryRepository {
    private static final String COLUMNS = "id,user_id,category,content,status,confidence,source_type,"
            + "source_conversation_id,created_at,updated_at,last_used_at";

    private final JdbcTemplate jdbc;

    public LongTermMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(LongTermMemory memory) {
        jdbc.update("INSERT INTO jarvis.long_term_memories (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                memory.id(), memory.userId(), memory.category(), memory.content(), memory.status().name(),
                memory.confidence(), memory.sourceType().name(), memory.sourceConversationId(),
                Timestamp.from(memory.createdAt()), Timestamp.from(memory.updatedAt()), timestamp(memory.lastUsedAt()));
    }

    public Optional<LongTermMemory> findByIdAndUserId(UUID id, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis.long_term_memories WHERE id=? AND user_id=?",
                (rs, row) -> map(rs), id, userId).stream().findFirst();
    }

    public List<LongTermMemory> findAllByUserId(UUID userId) {
        return findAllByUserId(userId, null, null);
    }

    public List<LongTermMemory> findAllByUserId(UUID userId, String category, MemoryStatus status) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis.long_term_memories WHERE user_id=? "
                        + "AND (CAST(? AS VARCHAR) IS NULL OR category=?) "
                        + "AND (CAST(? AS VARCHAR) IS NULL OR status=?) "
                        + "ORDER BY updated_at DESC, id",
                (rs, row) -> map(rs), userId, category, category,
                status == null ? null : status.name(), status == null ? null : status.name());
    }

    public List<LongTermMemory> findPageByUserId(
            UUID userId, String category, MemoryStatus status, int limit, long offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jarvis.long_term_memories WHERE user_id=? "
                        + "AND (CAST(? AS VARCHAR) IS NULL OR category=?) "
                        + "AND (CAST(? AS VARCHAR) IS NULL OR status=?) "
                        + "ORDER BY updated_at DESC, id LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), userId, category, category,
                status == null ? null : status.name(), status == null ? null : status.name(), limit, offset);
    }

    public long countByUserId(UUID userId, String category, MemoryStatus status) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.long_term_memories WHERE user_id=? "
                        + "AND (CAST(? AS VARCHAR) IS NULL OR category=?) "
                        + "AND (CAST(? AS VARCHAR) IS NULL OR status=?)",
                Long.class, userId, category, category,
                status == null ? null : status.name(), status == null ? null : status.name());
        return count == null ? 0 : count;
    }

    public boolean updateOwnedFields(
            UUID id, UUID userId, String category, String content, MemoryStatus status, java.time.Instant updatedAt) {
        return jdbc.update("UPDATE jarvis.long_term_memories SET category=?,content=?,status=?,updated_at=? "
                        + "WHERE id=? AND user_id=?",
                category, content, status.name(), Timestamp.from(updatedAt), id, userId) == 1;
    }

    public boolean update(LongTermMemory memory) {
        return jdbc.update("UPDATE jarvis.long_term_memories SET category=?,content=?,status=?,confidence=?,"
                        + "source_type=?,source_conversation_id=?,updated_at=?,last_used_at=? WHERE id=? AND user_id=?",
                memory.category(), memory.content(), memory.status().name(), memory.confidence(),
                memory.sourceType().name(), memory.sourceConversationId(), Timestamp.from(memory.updatedAt()),
                timestamp(memory.lastUsedAt()), memory.id(), memory.userId()) == 1;
    }

    public boolean deleteByIdAndUserId(UUID id, UUID userId) {
        return jdbc.update("DELETE FROM jarvis.long_term_memories WHERE id=? AND user_id=?", id, userId) == 1;
    }

    public int unlinkSourceConversation(UUID conversationId, UUID userId) {
        return jdbc.update("UPDATE jarvis.long_term_memories SET source_conversation_id=NULL "
                        + "WHERE source_conversation_id=? AND user_id=?", conversationId, userId);
    }

    private LongTermMemory map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LongTermMemory(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("category"), rs.getString("content"), MemoryStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("confidence"), MemorySourceType.valueOf(rs.getString("source_type")),
                rs.getObject("source_conversation_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), instant(rs.getTimestamp("last_used_at")));
    }

    private static Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
