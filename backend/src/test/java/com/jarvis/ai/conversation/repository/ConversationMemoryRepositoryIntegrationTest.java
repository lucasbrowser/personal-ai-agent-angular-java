package com.jarvis.ai.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jarvis.ai.conversation.domain.Conversation;
import com.jarvis.ai.conversation.domain.ConversationMessage;
import com.jarvis.ai.conversation.domain.ConversationStatus;
import com.jarvis.ai.conversation.domain.MessageRole;
import com.jarvis.ai.conversation.domain.MessageSource;
import com.jarvis.ai.memory.domain.LongTermMemory;
import com.jarvis.ai.memory.domain.MemorySourceType;
import com.jarvis.ai.memory.domain.MemoryStatus;
import com.jarvis.ai.memory.repository.LongTermMemoryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ConversationMemoryRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static JdbcTemplate jdbc;
    private static ConversationRepository conversations;
    private static ConversationMessageRepository messages;
    private static LongTermMemoryRepository memories;
    private static Flyway flyway;

    @BeforeAll
    static void initializeDatabase() {
        flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        conversations = new ConversationRepository(jdbc);
        messages = new ConversationMessageRepository(jdbc);
        memories = new LongTermMemoryRepository(jdbc);
    }

    @Test
    void appliesAllMigrationsAndCreatesExpectedTablesAndIndexes() {
        assertThat(flyway.info().applied()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='jarvis' "
                + "AND table_name IN ('users','refresh_tokens','conversations','conversation_messages','long_term_memories')",
                Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='jarvis' "
                + "AND indexname IN ('idx_conversations_user_updated','idx_conversation_messages_timeline',"
                + "'uq_conversation_messages_external_event','idx_long_term_memories_user_status_category',"
                + "'idx_long_term_memories_source_conversation','uq_conversations_id_user_id')", Integer.class))
                .isEqualTo(6);
    }

    @Test
    void savesAndFindsConversationsScopedToTheirOwner() {
        UUID owner = user();
        UUID otherOwner = user();
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        Conversation conversation = conversation(owner, now, "Jarvis planning");
        conversations.save(conversation);

        assertThat(conversations.findByIdAndUserId(conversation.id(), owner)).contains(conversation);
        assertThat(conversations.findByIdAndUserId(conversation.id(), otherOwner)).isEmpty();
        assertThat(conversations.findByIdAndUserId(UUID.randomUUID(), owner)).isEmpty();
        assertThat(conversations.findAllByUserId(owner)).containsExactly(conversation);
        assertThat(conversations.findAllByUserId(otherOwner)).isEmpty();
    }

    @Test
    void savesMessagesChronologicallyDeduplicatesEventsAndCascadesOnConversationDelete() {
        UUID owner = user();
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        Conversation conversation = conversation(owner, now, "Voice session");
        conversations.save(conversation);

        ConversationMessage later = message(conversation, owner, MessageRole.ASSISTANT,
                MessageSource.REALTIME_ASSISTANT_TRANSCRIPT, "event-assistant", now.plusSeconds(3));
        ConversationMessage earlier = message(conversation, owner, MessageRole.USER,
                MessageSource.REALTIME_USER_TRANSCRIPT, "event-user", now.plusSeconds(1));
        assertThat(messages.save(later)).isTrue();
        assertThat(messages.save(earlier)).isTrue();
        assertThat(messages.save(message(conversation, owner, MessageRole.USER,
                MessageSource.REALTIME_USER_TRANSCRIPT, "event-user", now.plusSeconds(4)))).isFalse();
        assertThat(messages.findAllByConversationAndUserId(conversation.id(), owner))
                .extracting(ConversationMessage::externalEventId)
                .containsExactly("event-user", "event-assistant");
        assertThat(messages.findAllByConversationAndUserId(conversation.id(), UUID.randomUUID())).isEmpty();

        UUID otherOwner = user();
        assertThatThrownBy(() -> messages.save(message(conversation, otherOwner, MessageRole.USER,
                MessageSource.REALTIME_USER_TRANSCRIPT, null, now)))
                .isInstanceOf(DataIntegrityViolationException.class);

        LongTermMemory memory = memory(owner, now, conversation.id(), new BigDecimal("0.80"));
        memories.save(memory);
        assertThat(conversations.deleteByIdAndUserId(conversation.id(), UUID.randomUUID())).isFalse();
        assertThat(conversations.deleteByIdAndUserId(conversation.id(), owner)).isTrue();
        assertThat(messages.findAllByConversationAndUserId(conversation.id(), owner)).isEmpty();
        assertThat(memories.findByIdAndUserId(memory.id(), owner)).get()
                .extracting(LongTermMemory::sourceConversationId).isNull();
    }

    @Test
    void createsFiltersUpdatesAndPhysicallyDeletesMemoriesWithOwnerIsolation() {
        UUID owner = user();
        UUID otherOwner = user();
        Instant now = Instant.parse("2026-11-04T13:00:00Z");
        LongTermMemory active = memory(owner, now, null, new BigDecimal("0.75"));
        LongTermMemory inactive = new LongTermMemory(UUID.randomUUID(), owner, "PREFERENCE", "Prefers concise replies",
                MemoryStatus.INACTIVE, null, MemorySourceType.USER_PROVIDED, null, now, now, null);
        memories.save(active);
        memories.save(inactive);

        assertThat(memories.findByIdAndUserId(active.id(), owner)).contains(active);
        assertThat(memories.findByIdAndUserId(active.id(), otherOwner)).isEmpty();
        assertThat(memories.findByIdAndUserId(UUID.randomUUID(), owner)).isEmpty();
        assertThat(memories.findAllByUserId(owner)).hasSize(2);
        assertThat(memories.findAllByUserId(owner, "PREFERENCE", MemoryStatus.INACTIVE)).containsExactly(inactive);
        assertThat(memories.findAllByUserId(otherOwner)).isEmpty();

        LongTermMemory updated = new LongTermMemory(active.id(), owner, "PROFILE", "Lives in Cuiabá",
                MemoryStatus.INACTIVE, new BigDecimal("1.00"), MemorySourceType.USER_PROVIDED, null,
                active.createdAt(), now.plusSeconds(10), now.plusSeconds(5));
        assertThat(memories.update(updated)).isTrue();
        assertThat(memories.findByIdAndUserId(active.id(), owner)).contains(updated);
        assertThat(memories.update(new LongTermMemory(active.id(), otherOwner, "PROFILE", "forged",
                MemoryStatus.ACTIVE, null, MemorySourceType.USER_PROVIDED, null, now, now, null))).isFalse();
        assertThat(memories.deleteByIdAndUserId(active.id(), otherOwner)).isFalse();
        assertThat(memories.deleteByIdAndUserId(active.id(), owner)).isTrue();
        assertThat(memories.findByIdAndUserId(active.id(), owner)).isEmpty();

        LongTermMemory invalidConfidence = memory(owner, now, null, new BigDecimal("1.01"));
        assertThatThrownBy(() -> memories.save(invalidConfidence))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postgresConstraintsRejectInvalidStatesRolesSourcesAndCrossOwnerMemoryOrigins() {
        UUID owner = user();
        UUID otherOwner = user();
        Instant now = Instant.parse("2026-11-04T14:00:00Z");
        Conversation conversation = conversation(owner, now, "Owned conversation");
        conversations.save(conversation);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO jarvis.conversations "
                        + "(id,user_id,status,created_at,updated_at) VALUES (?,?, 'UNKNOWN', ?,?)",
                UUID.randomUUID(), owner, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO jarvis.conversation_messages "
                        + "(id,conversation_id,user_id,role,content,source,created_at) VALUES (?,?,?,'SYSTEM','x',?,?)",
                UUID.randomUUID(), conversation.id(), owner, MessageSource.REALTIME_USER_TRANSCRIPT.name(),
                java.sql.Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO jarvis.conversation_messages "
                        + "(id,conversation_id,user_id,role,content,source,created_at) VALUES (?,?,?,'USER','x','UNKNOWN',?)",
                UUID.randomUUID(), conversation.id(), owner, java.sql.Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO jarvis.conversation_messages "
                        + "(id,conversation_id,user_id,role,content,source,created_at) "
                        + "VALUES (?,?,?,'USER','x','REALTIME_ASSISTANT_TRANSCRIPT',?)",
                UUID.randomUUID(), conversation.id(), owner, java.sql.Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> memories.save(memory(otherOwner, now, conversation.id(), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO jarvis.users(id,name,email,password_hash,role,enabled) VALUES(?,?,?,'test-hash','USER',true)",
                id, "Integration Test", id + "@example.test");
        return id;
    }

    private static Conversation conversation(UUID owner, Instant createdAt, String title) {
        return new Conversation(UUID.randomUUID(), owner, title, ConversationStatus.ACTIVE,
                createdAt, createdAt, null);
    }

    private static ConversationMessage message(Conversation conversation, UUID owner, MessageRole role,
                                               MessageSource source, String eventId, Instant createdAt) {
        return new ConversationMessage(UUID.randomUUID(), conversation.id(), owner, role,
                role == MessageRole.USER ? "Hello" : "Hi", source, eventId, createdAt);
    }

    private static LongTermMemory memory(UUID owner, Instant createdAt, UUID sourceConversationId,
                                         BigDecimal confidence) {
        return new LongTermMemory(UUID.randomUUID(), owner, "PROFILE", "Lives in Cuiabá", MemoryStatus.ACTIVE,
                confidence, sourceConversationId == null ? MemorySourceType.USER_PROVIDED
                        : MemorySourceType.CONVERSATION_EXTRACTED,
                sourceConversationId, createdAt, createdAt, null);
    }
}
