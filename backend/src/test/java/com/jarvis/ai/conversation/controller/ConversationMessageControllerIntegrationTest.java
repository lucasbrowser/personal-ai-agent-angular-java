package com.jarvis.ai.conversation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationMessageControllerIntegrationTest extends ConversationApiIntegrationTestBase {
    @Test
    @SuppressWarnings("unchecked")
    void persistsUserAndAssistantMessagesAndReturnsChronologicalPages() throws Exception {
        UUID owner = createUser();
        UUID conversationId = createConversation(owner);
        var userMessage = send(messagePath(conversationId), "POST", message("USER", "Hello", "REALTIME_USER_TRANSCRIPT", null), owner);
        var assistantMessage = send(messagePath(conversationId), "POST",
                message("ASSISTANT", "Hi there", "REALTIME_ASSISTANT_TRANSCRIPT", null), owner);
        assertThat(userMessage.statusCode()).isEqualTo(201);
        assertThat(assistantMessage.statusCode()).isEqualTo(201);

        var pageZero = send(messagePath(conversationId) + "?page=0&size=1", "GET", null, owner);
        var pageOne = send(messagePath(conversationId) + "?page=1&size=1", "GET", null, owner);
        Map<String, Object> firstPage = body(pageZero);
        Map<String, Object> secondPage = body(pageOne);
        Map<String, Object> firstItem = (Map<String, Object>) ((List<?>) firstPage.get("items")).getFirst();
        Map<String, Object> secondItem = (Map<String, Object>) ((List<?>) secondPage.get("items")).getFirst();
        assertThat(firstItem).containsEntry("role", "USER").containsEntry("content", "Hello");
        assertThat(secondItem).containsEntry("role", "ASSISTANT").containsEntry("content", "Hi there");
        assertThat(firstPage).containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        assertThat(body(send(messagePath(conversationId), "GET", null, owner)).get("items"))
                .asList().extracting(item -> ((Map<String, Object>) item).get("role"))
                .containsExactly("USER", "ASSISTANT");
        assertThat(send(messagePath(conversationId) + "?page=0&size=101", "GET", null, owner).statusCode()).isEqualTo(400);
        assertThat(send(messagePath(conversationId) + "?page=-1", "GET", null, owner).statusCode()).isEqualTo(400);

        Timestamp lastMessageAt = jdbc.queryForObject("SELECT last_message_at FROM jarvis.conversations WHERE id=?",
                Timestamp.class, conversationId);
        assertThat(lastMessageAt).isNotNull();
    }

    @Test
    void isIdempotentAndDoesNotUpdateConversationForRepeatedExternalEvent() throws Exception {
        UUID owner = createUser();
        UUID conversationId = createConversation(owner);
        Map<String, Object> firstRequest = message("USER", "Original transcript", "REALTIME_USER_TRANSCRIPT", "evt-123");

        var first = send(messagePath(conversationId), "POST", firstRequest, owner);
        assertThat(first.statusCode()).isEqualTo(201);
        Map<String, Object> firstResponse = body(first);
        Timestamp updatedAt = jdbc.queryForObject("SELECT updated_at FROM jarvis.conversations WHERE id=?",
                Timestamp.class, conversationId);
        Timestamp lastMessageAt = jdbc.queryForObject("SELECT last_message_at FROM jarvis.conversations WHERE id=?",
                Timestamp.class, conversationId);

        var duplicate = send(messagePath(conversationId), "POST",
                message("USER", "A different retry body", "REALTIME_USER_TRANSCRIPT", "evt-123"), owner);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        Map<String, Object> duplicateResponse = body(duplicate);
        assertThat(duplicateResponse).containsEntry("id", firstResponse.get("id"))
                .containsEntry("content", "Original transcript");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.conversation_messages WHERE conversation_id=?",
                Integer.class, conversationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM jarvis.conversations WHERE id=?",
                Timestamp.class, conversationId)).isEqualTo(updatedAt);
        assertThat(jdbc.queryForObject("SELECT last_message_at FROM jarvis.conversations WHERE id=?",
                Timestamp.class, conversationId)).isEqualTo(lastMessageAt);
    }

    @Test
    void rejectsInvalidContentRoleSourceSizeAndRoleSourcePair() throws Exception {
        UUID owner = createUser();
        UUID conversationId = createConversation(owner);

        assertBadRequest(message("USER", "  ", "REALTIME_USER_TRANSCRIPT", null), owner, conversationId);
        assertBadRequest(message("SYSTEM", "Hello", "REALTIME_USER_TRANSCRIPT", null), owner, conversationId);
        assertBadRequest(message("USER", "Hello", "UNKNOWN", null), owner, conversationId);
        assertBadRequest(message("USER", "Hello", "REALTIME_ASSISTANT_TRANSCRIPT", null), owner, conversationId);
        assertBadRequest(message("USER", "x".repeat(20_001), "REALTIME_USER_TRANSCRIPT", null), owner, conversationId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.conversation_messages WHERE conversation_id=?",
                Integer.class, conversationId)).isZero();
    }

    @Test
    void returnsNotFoundForMissingOrForeignConversationAndRequiresAuthentication() throws Exception {
        UUID owner = createUser();
        UUID otherOwner = createUser();
        UUID conversationId = createConversation(owner);
        String path = messagePath(conversationId);

        assertThat(send(path, "GET", null, otherOwner).statusCode()).isEqualTo(404);
        assertThat(send(path, "POST", message("USER", "Hello", "REALTIME_USER_TRANSCRIPT", null), otherOwner)
                .statusCode()).isEqualTo(404);
        assertThat(send(messagePath(UUID.randomUUID()), "GET", null, owner).statusCode()).isEqualTo(404);
        assertThat(send(path, "GET", null, null).statusCode()).isEqualTo(401);
        assertThat(send("/api/v1/conversations/not-a-uuid/messages", "GET", null, owner).statusCode()).isEqualTo(400);
    }

    private UUID createConversation(UUID owner) throws Exception {
        var response = send("/api/v1/conversations", "POST", Map.of(), owner);
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(body(response).get("id").toString());
    }

    private Map<String, Object> message(String role, String content, String source, String externalEventId) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("role", role);
        request.put("content", content);
        request.put("source", source);
        if (externalEventId != null) request.put("externalEventId", externalEventId);
        return request;
    }

    private String messagePath(UUID conversationId) {
        return "/api/v1/conversations/" + conversationId + "/messages";
    }

    private void assertBadRequest(Map<String, Object> request, UUID owner, UUID conversationId) throws Exception {
        var response = send(messagePath(conversationId), "POST", request, owner);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(error(response)).containsEntry("code", "INVALID_REQUEST");
        assertThat(response.body()).doesNotContain("SQL", "stackTrace", "password_hash");
    }
}
