package com.jarvis.ai.conversation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationControllerIntegrationTest extends ConversationApiIntegrationTestBase {
    @Test
    void createsConversationWithAuthenticatedOwnerAndOptionalTitle() throws Exception {
        UUID owner = createUser();
        var response = send("/api/v1/conversations", "POST", Map.of("title", " Planning ", "userId", UUID.randomUUID()), owner);

        assertThat(response.statusCode()).isEqualTo(201);
        Map<String, Object> conversation = body(response);
        assertThat(conversation).containsEntry("title", "Planning").containsEntry("status", "ACTIVE");
        UUID conversationId = UUID.fromString(conversation.get("id").toString());
        assertThat(jdbc.queryForObject("SELECT user_id FROM jarvis.conversations WHERE id=?", UUID.class, conversationId))
                .isEqualTo(owner);

        var untitled = send("/api/v1/conversations", "POST", null, owner);
        assertThat(untitled.statusCode()).isEqualTo(201);
        assertThat(body(untitled)).containsEntry("title", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsOnlyOwnedConversationsAndPagesDeterministically() throws Exception {
        UUID owner = createUser();
        UUID otherOwner = createUser();
        UUID first = createConversation(owner, "First");
        UUID second = createConversation(owner, "Second");
        createConversation(owner, "Third");
        createConversation(otherOwner, "Private to other user");

        var pageZero = send("/api/v1/conversations?page=0&size=1", "GET", null, owner);
        var pageOne = send("/api/v1/conversations?page=1&size=1", "GET", null, owner);
        assertThat(pageZero.statusCode()).isEqualTo(200);
        Map<String, Object> firstPage = body(pageZero);
        Map<String, Object> secondPage = body(pageOne);
        assertThat(firstPage).containsEntry("page", 0).containsEntry("size", 1)
                .containsEntry("totalElements", 3).containsEntry("totalPages", 3);
        UUID pageZeroId = UUID.fromString(((Map<String, Object>) ((List<?>) firstPage.get("items")).getFirst()).get("id").toString());
        UUID pageOneId = UUID.fromString(((Map<String, Object>) ((List<?>) secondPage.get("items")).getFirst()).get("id").toString());
        List<UUID> orderedIds = jdbc.query("SELECT id FROM jarvis.conversations WHERE user_id=? ORDER BY updated_at DESC, id",
                (rs, row) -> rs.getObject("id", UUID.class), owner);
        assertThat(List.of(pageZeroId, pageOneId)).containsExactly(orderedIds.get(0), orderedIds.get(1))
                .doesNotHaveDuplicates();

        assertThat(send("/api/v1/conversations?page=-1", "GET", null, owner).statusCode()).isEqualTo(400);
        assertThat(send("/api/v1/conversations?size=101", "GET", null, owner).statusCode()).isEqualTo(400);
        assertThat(send("/api/v1/conversations", "GET", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void hidesForeignAndMissingConversationsAndRequiresAuthentication() throws Exception {
        UUID owner = createUser();
        UUID otherOwner = createUser();
        UUID conversationId = createConversation(owner, "Private");

        assertThat(send("/api/v1/conversations/" + conversationId, "GET", null, owner).statusCode()).isEqualTo(200);
        var foreign = send("/api/v1/conversations/" + conversationId, "GET", null, otherOwner);
        var missing = send("/api/v1/conversations/" + UUID.randomUUID(), "GET", null, otherOwner);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(error(foreign)).containsEntry("code", "NOT_FOUND");
        assertThat(error(missing)).containsEntry("code", "NOT_FOUND");
        assertThat(send("/api/v1/conversations/" + conversationId, "GET", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void deletionCascadesMessagesAndUnlinksButKeepsMemory() throws Exception {
        UUID owner = createUser();
        UUID conversationId = createConversation(owner, "To delete");
        var message = send("/api/v1/conversations/" + conversationId + "/messages", "POST",
                Map.of("role", "USER", "content", "Hello", "source", "REALTIME_USER_TRANSCRIPT"), owner);
        assertThat(message.statusCode()).isEqualTo(201);

        UUID memoryId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO jarvis.long_term_memories(id,user_id,category,content,status,source_type,"
                        + "source_conversation_id,created_at,updated_at) VALUES(?,?,?,'Remember me','ACTIVE',?,?,?,?)",
                memoryId, owner, "PROFILE", "CONVERSATION_EXTRACTED", conversationId,
                Timestamp.from(now), Timestamp.from(now));

        assertThat(send("/api/v1/conversations/" + conversationId, "DELETE", null, UUID.randomUUID()).statusCode())
                .isEqualTo(404);
        var deleted = send("/api/v1/conversations/" + conversationId, "DELETE", null, owner);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.conversation_messages WHERE conversation_id=?",
                Integer.class, conversationId)).isZero();
        assertThat(jdbc.queryForObject("SELECT source_conversation_id FROM jarvis.long_term_memories WHERE id=?",
                UUID.class, memoryId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.long_term_memories WHERE id=?", Integer.class, memoryId))
                .isEqualTo(1);
        assertThat(send("/api/v1/conversations/" + conversationId, "DELETE", null, owner).statusCode()).isEqualTo(404);
    }

    private UUID createConversation(UUID owner, String title) throws Exception {
        var response = send("/api/v1/conversations", "POST", Map.of("title", title), owner);
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(body(response).get("id").toString());
    }
}
