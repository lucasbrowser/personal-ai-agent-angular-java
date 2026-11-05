package com.jarvis.ai.conversation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class VoiceTurnControllerIntegrationTest extends ConversationApiIntegrationTestBase {
    @DynamicPropertySource
    static void contextLimits(DynamicPropertyRegistry registry) {
        registry.add("jarvis.conversation.context.max-recent-messages", () -> 3);
        registry.add("jarvis.conversation.context.max-memories", () -> 2);
        registry.add("jarvis.conversation.context.max-characters", () -> 200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsUserTurnAndReturnsOnlyBoundedRecentHistoryAndActiveOwnerMemories() throws Exception {
        UUID owner = createUser();
        UUID foreignOwner = createUser();
        UUID conversationId = createConversation(owner);
        UUID otherConversation = createConversation(owner);
        for (int i = 1; i <= 4; i++) {
            var inserted = send(messagePath(conversationId), "POST",
                    userMessage("m".repeat(30), "evt-prev-" + i), owner);
            assertThat(inserted.statusCode()).isEqualTo(201);
        }
        send(messagePath(otherConversation), "POST", userMessage("not included", "evt-other"), owner);
        insertMemory(owner, "PREFERENCE", "memory-01", "0.90", "ACTIVE");
        insertMemory(owner, "PROFILE", "memory-02", "0.80", "ACTIVE");
        insertMemory(owner, "PROFILE", "inactive-secret", "1.00", "INACTIVE");
        insertMemory(foreignOwner, "PROFILE", "foreign-secret", "1.00", "ACTIVE");

        var response = send(turnPath(conversationId), "POST",
                turn("final user words", "evt-final"), owner);
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> result = body(response);
        assertThat(result).containsEntry("created", true);
        Map<String, Object> persisted = (Map<String, Object>) result.get("persistedMessage");
        assertThat(persisted).containsEntry("role", "USER").containsEntry("source", "REALTIME_USER_TRANSCRIPT")
                .containsEntry("content", "final user words").containsEntry("externalEventId", "evt-final");
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("recentMessages");
        assertThat(messages).hasSize(3)
                .extracting(message -> message.get("content"))
                .containsExactly("m".repeat(30), "m".repeat(30), "final user words");
        List<Map<String, Object>> memories = (List<Map<String, Object>>) result.get("activeMemories");
        assertThat(memories).hasSize(2)
                .extracting(memory -> memory.get("content"))
                .containsExactly("memory-01", "memory-02");
        Map<String, Object> budget = (Map<String, Object>) result.get("contextBudget");
        assertThat(budget).containsEntry("maxCharacters", 200).containsEntry("selectedCharacters", 133);
        assertThat(response.body()).doesNotContain("inactive-secret", "foreign-secret", "not included");
    }

    @Test
    @SuppressWarnings("unchecked")
    void repeatedEventReturnsOriginalMessageWithoutUpdatingConversationTimestamps() throws Exception {
        UUID owner = createUser();
        UUID conversationId = createConversation(owner);
        var first = send(turnPath(conversationId), "POST", turn("first body", "evt-idempotent"), owner);
        assertThat(first.statusCode()).isEqualTo(200);
        Map<String, Object> firstBody = body(first);
        Map<String, Object> firstMessage = (Map<String, Object>) firstBody.get("persistedMessage");
        Timestamp updated = jdbc.queryForObject("SELECT updated_at FROM jarvis.conversations WHERE id=?", Timestamp.class, conversationId);
        Timestamp last = jdbc.queryForObject("SELECT last_message_at FROM jarvis.conversations WHERE id=?", Timestamp.class, conversationId);

        var repeated = send(turnPath(conversationId), "POST", turn("changed retry", "evt-idempotent"), owner);
        assertThat(repeated.statusCode()).isEqualTo(200);
        Map<String, Object> repeatedBody = body(repeated);
        assertThat(repeatedBody).containsEntry("created", false);
        Map<String, Object> repeatedMessage = (Map<String, Object>) repeatedBody.get("persistedMessage");
        assertThat(repeatedMessage).containsEntry("id", firstMessage.get("id"))
                .containsEntry("content", "first body")
                .containsEntry("externalEventId", "evt-idempotent");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.conversation_messages WHERE conversation_id=?",
                Integer.class, conversationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM jarvis.conversations WHERE id=?", Timestamp.class, conversationId)).isEqualTo(updated);
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM jarvis.conversations WHERE id=?", Timestamp.class, conversationId)).isEqualTo(last);
    }

    @Test
    void rejectsInvalidContentAndUnrecognizedAuthorityFields() throws Exception {
        UUID owner = createUser();
        UUID conversationId = createConversation(owner);
        assertThat(send(turnPath(conversationId), "POST", turn("  ", "evt-empty"), owner).statusCode()).isEqualTo(400);
        assertThat(send(turnPath(conversationId), "POST", turn("x".repeat(20_001), "evt-long"), owner).statusCode()).isEqualTo(400);
        Map<String, Object> forbidden = turn("hello", "evt-forbidden");
        forbidden.put("role", "ASSISTANT");
        forbidden.put("userId", UUID.randomUUID().toString());
        assertThat(send(turnPath(conversationId), "POST", forbidden, owner).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.conversation_messages WHERE conversation_id=?",
                Integer.class, conversationId)).isZero();
    }

    @Test
    void requiresAuthenticationAndHidesMissingOrForeignConversations() throws Exception {
        UUID owner = createUser();
        UUID other = createUser();
        UUID conversationId = createConversation(owner);
        assertThat(send(turnPath(conversationId), "POST", turn("hello", "evt-auth"), null).statusCode()).isEqualTo(401);
        assertThat(send(turnPath(conversationId), "POST", turn("hello", "evt-foreign"), other).statusCode()).isEqualTo(404);
        assertThat(send(turnPath(UUID.randomUUID()), "POST", turn("hello", "evt-missing"), owner).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.conversation_messages WHERE conversation_id=?",
                Integer.class, conversationId)).isZero();
    }

    @Test
    void persistenceFailureDoesNotReturnReadyTurnOrLeavePartialMessage() throws Exception {
        UUID owner = createUser();
        UUID conversationId = createConversation(owner);
        jdbc.execute("CREATE FUNCTION jarvis.reject_voice_turn() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced persistence failure'; END; $$");
        jdbc.execute("CREATE TRIGGER reject_voice_turn BEFORE INSERT ON jarvis.conversation_messages "
                + "FOR EACH ROW WHEN (NEW.external_event_id = 'evt-fail') EXECUTE FUNCTION jarvis.reject_voice_turn()");
        try {
            var response = send(turnPath(conversationId), "POST", turn("hello", "evt-fail"), owner);
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(body(response)).doesNotContainKeys("persistedMessage", "recentMessages", "activeMemories");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.conversation_messages WHERE conversation_id=?",
                    Integer.class, conversationId)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_voice_turn ON jarvis.conversation_messages");
            jdbc.execute("DROP FUNCTION jarvis.reject_voice_turn()");
        }
    }

    private UUID createConversation(UUID owner) throws Exception {
        var response = send("/api/v1/conversations", "POST", Map.of(), owner);
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(body(response).get("id").toString());
    }

    private void insertMemory(UUID owner, String category, String content, String confidence, String status) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO jarvis.long_term_memories (id,user_id,category,content,status,confidence,source_type,"
                        + "created_at,updated_at) VALUES (?,?,?,?,?::varchar,?::numeric,'USER_PROVIDED',?,?)",
                UUID.randomUUID(), owner, category, content, status, new java.math.BigDecimal(confidence),
                Timestamp.from(now), Timestamp.from(now));
    }

    private Map<String, Object> userMessage(String content, String eventId) {
        return Map.of("role", "USER", "content", content, "source", "REALTIME_USER_TRANSCRIPT", "externalEventId", eventId);
    }

    private Map<String, Object> turn(String content, String eventId) {
        return new HashMap<>(Map.of("content", content, "externalEventId", eventId));
    }

    private String messagePath(UUID id) { return "/api/v1/conversations/" + id + "/messages"; }
    private String turnPath(UUID id) { return "/api/v1/conversations/" + id + "/voice-turns"; }
}
