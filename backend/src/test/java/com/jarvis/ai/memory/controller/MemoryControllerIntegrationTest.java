package com.jarvis.ai.memory.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemoryControllerIntegrationTest {
    private static final String ORIGIN = "http://localhost:4200";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jarvis.jwt.secret", () -> "integration-test-secret-with-at-least-32-bytes");
        registry.add("jarvis.frontend-origin", () -> ORIGIN);
        registry.add("jarvis.openai.realtime.api-key", () -> "test-openai-api-key-not-real");
        registry.add("jarvis.admin.email", () -> "memory-admin@example.test");
        registry.add("jarvis.admin.password", () -> "test-admin-password-long-enough");
    }

    @Value("${local.server.port}")
    int port;
    private final HttpClient http = HttpClient.newHttpClient();
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsManualMemoryWithAuthenticatedOwnerAndServerControlledProvenance() throws Exception {
        UUID owner = createUser();
        UUID forgedOwner = createUser();
        Map<String, Object> request = Map.of(
                "category", " PROFILE ", "content", "Remember my preference", "userId", forgedOwner,
                "sourceType", "CONVERSATION_EXTRACTED", "confidence", new BigDecimal("1.00"),
                "sourceConversationId", UUID.randomUUID());

        var response = send("/api/v1/memories", "POST", request, owner);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).isPresent();
        Map<String, Object> body = body(response);
        UUID id = UUID.fromString(body.get("id").toString());
        assertThat(body).containsEntry("category", "PROFILE").containsEntry("content", "Remember my preference")
                .containsEntry("status", "ACTIVE").containsEntry("sourceType", "USER_PROVIDED")
                .containsEntry("confidence", null).containsEntry("sourceConversationId", null)
                .doesNotContainKey("userId");
        assertThat(jdbc.queryForObject("SELECT user_id FROM jarvis.long_term_memories WHERE id=?", UUID.class, id))
                .isEqualTo(owner);
    }

    @Test
    void rejectsInvalidCreationFieldsAndContentAboveApiLimit() throws Exception {
        UUID owner = createUser();
        assertBadRequest("/api/v1/memories", "POST", Map.of("category", "PROFILE", "content", "  "), owner);
        assertBadRequest("/api/v1/memories", "POST", Map.of("category", " ", "content", "Some content"), owner);
        assertBadRequest("/api/v1/memories", "POST",
                Map.of("category", "x".repeat(51), "content", "Some content"), owner);
        assertBadRequest("/api/v1/memories", "POST",
                Map.of("category", "PROFILE", "content", "x".repeat(20_001)), owner);
        assertThat(send("/api/v1/memories", "POST",
                Map.of("category", "PROFILE", "content", "Some content", "status", "UNKNOWN"), owner)
                .statusCode()).isEqualTo(201); // Creation DTO deliberately ignores client-supplied metadata.
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsOnlyOwnedMemoriesWithStablePaginationAndUsefulFilters() throws Exception {
        UUID owner = createUser();
        UUID otherOwner = createUser();
        UUID first = createMemory(owner, "PROFILE", "First", "ACTIVE");
        UUID second = createMemory(owner, "PROFILE", "Second", "INACTIVE");
        createMemory(owner, "PREFERENCE", "Third", "ACTIVE");
        createMemory(otherOwner, "PROFILE", "Private", "ACTIVE");

        Map<String, Object> page0 = body(send("/api/v1/memories?page=0&size=1&category=PROFILE", "GET", null, owner));
        Map<String, Object> page1 = body(send("/api/v1/memories?page=1&size=1&category=PROFILE", "GET", null, owner));
        assertThat(page0).containsEntry("page", 0).containsEntry("size", 1)
                .containsEntry("totalElements", 2).containsEntry("totalPages", 2);
        List<UUID> expectedOrder = jdbc.query(
                "SELECT id FROM jarvis.long_term_memories WHERE user_id=? AND category=? ORDER BY updated_at DESC, id",
                (rs, row) -> rs.getObject("id", UUID.class), owner, "PROFILE");
        assertThat(List.of(
                UUID.fromString(((Map<String, Object>) ((List<?>) page0.get("items")).getFirst()).get("id").toString()),
                UUID.fromString(((Map<String, Object>) ((List<?>) page1.get("items")).getFirst()).get("id").toString())))
                .containsExactlyElementsOf(expectedOrder);
        assertThat(expectedOrder).contains(first, second);
        Map<String, Object> activeProfile = body(send(
                "/api/v1/memories?category=PROFILE&status=ACTIVE", "GET", null, owner));
        assertThat(activeProfile).containsEntry("totalElements", 1);
        assertThat(send("/api/v1/memories?size=101", "GET", null, owner).statusCode()).isEqualTo(400);
        assertThat(send("/api/v1/memories?page=-1", "GET", null, owner).statusCode()).isEqualTo(400);
        assertThat(send("/api/v1/memories?status=INVALID", "GET", null, owner).statusCode()).isEqualTo(400);
        assertThat(send("/api/v1/memories", "GET", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void readsOnlyOwnedMemoryAndHidesForeignOrMissingIds() throws Exception {
        UUID owner = createUser();
        UUID otherOwner = createUser();
        UUID id = createMemory(owner, "PROFILE", "Owner memory", "ACTIVE");

        assertThat(send("/api/v1/memories/" + id, "GET", null, owner).statusCode()).isEqualTo(200);
        assertNotFound(send("/api/v1/memories/" + id, "GET", null, otherOwner));
        assertNotFound(send("/api/v1/memories/" + UUID.randomUUID(), "GET", null, owner));
        assertThat(send("/api/v1/memories/not-a-uuid", "GET", null, owner).statusCode()).isEqualTo(400);
    }

    @Test
    void updatesOnlyEditableFieldsAndPreservesProvenanceConfidenceAndDates() throws Exception {
        UUID owner = createUser();
        UUID forgedOwner = createUser();
        UUID conversationId = createConversation(owner);
        UUID id = createMemory(owner, "PROFILE", "Before", "ACTIVE");
        Instant usedAt = Instant.parse("2026-10-01T12:00:00Z");
        jdbc.update("UPDATE jarvis.long_term_memories SET confidence=?,source_type=?,source_conversation_id=?,last_used_at=? "
                        + "WHERE id=? AND user_id=?",
                new BigDecimal("0.75"), "CONVERSATION_EXTRACTED", conversationId, Timestamp.from(usedAt), id, owner);
        Timestamp createdAt = jdbc.queryForObject("SELECT created_at FROM jarvis.long_term_memories WHERE id=?",
                Timestamp.class, id);

        Map<String, Object> request = Map.of(
                "category", "PREFERENCE", "content", "Updated content", "status", "INACTIVE",
                "id", UUID.randomUUID(), "userId", forgedOwner, "sourceType", "USER_PROVIDED",
                "confidence", new BigDecimal("0.01"), "sourceConversationId", UUID.randomUUID());
        var response = send("/api/v1/memories/" + id, "PUT", request, owner);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response)).containsEntry("category", "PREFERENCE").containsEntry("content", "Updated content")
                .containsEntry("status", "INACTIVE").containsEntry("sourceType", "CONVERSATION_EXTRACTED");
        assertThat(((Number) body(response).get("confidence")).doubleValue()).isEqualTo(0.75);
        assertThat(jdbc.queryForObject("SELECT user_id FROM jarvis.long_term_memories WHERE id=?", UUID.class, id))
                .isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT source_conversation_id FROM jarvis.long_term_memories WHERE id=?",
                UUID.class, id)).isEqualTo(conversationId);
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM jarvis.long_term_memories WHERE id=?",
                Timestamp.class, id)).isEqualTo(Timestamp.from(usedAt));
        assertThat(jdbc.queryForObject("SELECT created_at FROM jarvis.long_term_memories WHERE id=?",
                Timestamp.class, id)).isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM jarvis.long_term_memories WHERE id=?",
                Timestamp.class, id)).isAfter(createdAt);
    }

    @Test
    void rejectsInvalidUpdatesAndReturnsNotFoundForForeignMemory() throws Exception {
        UUID owner = createUser();
        UUID otherOwner = createUser();
        UUID id = createMemory(owner, "PROFILE", "Original", "ACTIVE");

        assertBadRequest("/api/v1/memories/" + id, "PUT",
                Map.of("category", "PROFILE", "content", " ", "status", "ACTIVE"), owner);
        assertBadRequest("/api/v1/memories/" + id, "PUT",
                Map.of("category", "PROFILE", "content", "Valid", "status", "INVALID"), owner);
        assertThat(send("/api/v1/memories/" + id, "PUT",
                Map.of("category", "PROFILE", "content", "x".repeat(20_001), "status", "ACTIVE"), owner)
                .statusCode()).isEqualTo(400);
        assertNotFound(send("/api/v1/memories/" + id, "PUT",
                Map.of("category", "PROFILE", "content", "Changed", "status", "ACTIVE"), otherOwner));
        assertNotFound(send("/api/v1/memories/" + UUID.randomUUID(), "PUT",
                Map.of("category", "PROFILE", "content", "Changed", "status", "ACTIVE"), owner));
        assertThat(jdbc.queryForObject("SELECT content FROM jarvis.long_term_memories WHERE id=?", String.class, id))
                .isEqualTo("Original");
    }

    @Test
    void deletesOnlyOwnedMemoryAndLeavesOtherUsersDataUntouched() throws Exception {
        UUID owner = createUser();
        UUID otherOwner = createUser();
        UUID ownId = createMemory(owner, "PROFILE", "Delete this", "ACTIVE");
        UUID otherId = createMemory(otherOwner, "PROFILE", "Keep this", "ACTIVE");

        assertNotFound(send("/api/v1/memories/" + otherId, "DELETE", null, owner));
        var deleted = send("/api/v1/memories/" + ownId, "DELETE", null, owner);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertNotFound(send("/api/v1/memories/" + ownId, "DELETE", null, owner));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jarvis.long_term_memories WHERE id=?", Integer.class, otherId))
                .isEqualTo(1);
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO jarvis.users(id,name,email,password_hash,role,enabled) "
                        + "VALUES(?,?,?,'test-hash','USER',true)",
                id, "Memory Test", id + "@memory.test");
        return id;
    }

    private UUID createMemory(UUID userId, String category, String content, String status) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO jarvis.long_term_memories(id,user_id,category,content,status,source_type,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,'USER_PROVIDED',?,?)",
                id, userId, category, content, status, Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private UUID createConversation(UUID userId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO jarvis.conversations(id,user_id,title,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',?,?)",
                id, userId, "Memory test conversation", Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private String bearerToken(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("jarvis").issuedAt(now).expiresAt(now.plusSeconds(900))
                .subject(userId.toString()).claim("roles", List.of("USER"))
                .claim("email", userId + "@memory.test").claim("name", "Memory Test").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private HttpResponse<String> send(String path, String method, Object requestBody, UUID authenticatedUser)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authenticatedUser != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken(authenticatedUser));
        }
        if (requestBody != null) {
            request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            request.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> body(HttpResponse<String> response) throws Exception {
        return mapper.readValue(response.body(), new TypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> error(HttpResponse<String> response) throws Exception {
        return (Map<String, Object>) body(response).get("error");
    }

    private void assertBadRequest(String path, String method, Object request, UUID owner) throws Exception {
        var response = send(path, method, request, owner);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(error(response)).containsEntry("code", "INVALID_REQUEST");
        assertThat(response.body()).doesNotContain("SQL", "stackTrace", "password_hash");
    }

    private void assertNotFound(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(error(response)).containsEntry("code", "NOT_FOUND");
    }
}
