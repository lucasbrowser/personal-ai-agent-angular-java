package com.jarvis.ai.conversation.controller;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ConversationApiIntegrationTestBase {
    private static final String ORIGIN = "http://localhost:4200";

    // Keep one PostgreSQL instance for both integration-test classes so Spring's cached
    // application context never points at a container stopped after the first class.
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("jarvis.jwt.secret", () -> "integration-test-secret-with-at-least-32-bytes");
        registry.add("jarvis.frontend-origin", () -> ORIGIN);
        registry.add("jarvis.openai.realtime.api-key", () -> "test-openai-api-key-not-real");
        registry.add("jarvis.admin.email", () -> "conversation-admin@example.test");
        registry.add("jarvis.admin.password", () -> "test-admin-password-long-enough");
    }

    @Value("${local.server.port}")
    int port;
    final HttpClient http = HttpClient.newHttpClient();
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired JdbcTemplate jdbc;

    UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO jarvis.users(id,name,email,password_hash,role,enabled) "
                        + "VALUES(?,?,?,'test-hash','USER',true)",
                id, "Conversation Test", id + "@conversation.test");
        return id;
    }

    String bearerToken(UUID userId) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("jarvis").issuedAt(now).expiresAt(now.plusSeconds(900))
                .subject(userId.toString()).claim("roles", List.of("USER"))
                .claim("email", userId + "@conversation.test").claim("name", "Conversation Test").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    HttpResponse<String> send(String path, String method, Object requestBody, UUID authenticatedUser) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authenticatedUser != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken(authenticatedUser));
        if (requestBody != null) {
            request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            request.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> body(HttpResponse<String> response) throws Exception {
        return mapper.readValue(response.body(), new TypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> error(HttpResponse<String> response) throws Exception {
        return (Map<String, Object>) body(response).get("error");
    }
}
