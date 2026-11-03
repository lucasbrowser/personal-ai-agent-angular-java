package com.jarvis.ai.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final String ORIGIN = "http://localhost:4200";
    private static final String ADMIN_EMAIL = "admin@jarvis.test";
    private static final String ADMIN_PASSWORD = "test-password-that-is-long-enough";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("jarvis.jwt.secret", () -> "integration-test-secret-with-at-least-32-bytes");
        registry.add("jarvis.admin.email", () -> ADMIN_EMAIL);
        registry.add("jarvis.admin.password", () -> ADMIN_PASSWORD);
        registry.add("jarvis.frontend-origin", () -> ORIGIN);
    }

    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();
    @org.springframework.beans.factory.annotation.Autowired ObjectMapper mapper;

    @Test
    void logsInRefreshesRotatesAndLogsOut() throws Exception {
        HttpHeaders csrfHeaders = new HttpHeaders();
        csrfHeaders.set(HttpHeaders.ORIGIN, ORIGIN);
        var csrfResponse = send("/api/v1/auth/csrf", "GET", null, csrfHeaders);
        String csrf = body(csrfResponse).get("token").toString();
        assertThat(csrf).isNotBlank();
        String csrfCookie = cookieValue(csrfResponse, "XSRF-TOKEN");

        HttpHeaders loginHeaders = new HttpHeaders(); loginHeaders.setContentType(MediaType.APPLICATION_JSON); loginHeaders.setOrigin(ORIGIN);
        var login = send("/api/v1/auth/login", "POST", Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD), loginHeaders);
        assertThat(login.statusCode()).isEqualTo(200);
        String access = (String) body(login).get("accessToken");
        String oldRefresh = cookieValue(login, "jarvis_refresh");
        assertThat(access).isNotBlank();
        assertThat(oldRefresh).isNotBlank();

        HttpHeaders meHeaders = new HttpHeaders(); meHeaders.setBearerAuth(access);
        var me = send("/api/v1/auth/me", "GET", null, meHeaders);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(body(me)).containsEntry("email", ADMIN_EMAIL);

        HttpHeaders refreshHeaders = csrfHeaders(csrf, csrfCookie, oldRefresh);
        var refreshed = send("/api/v1/auth/refresh", "POST", Map.of(), refreshHeaders);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String newRefresh = cookieValue(refreshed, "jarvis_refresh");
        assertThat(newRefresh).isNotEqualTo(oldRefresh);

        var replay = send("/api/v1/auth/refresh", "POST", Map.of(), csrfHeaders(csrf, csrfCookie, oldRefresh));
        assertThat(replay.statusCode()).isEqualTo(401);

        HttpHeaders missingCsrf = new HttpHeaders(); missingCsrf.setOrigin(ORIGIN);
        missingCsrf.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfCookie + "; jarvis_refresh=" + newRefresh);
        var csrfRejected = send("/api/v1/auth/logout", "POST", Map.of(), missingCsrf);
        assertThat(csrfRejected.statusCode()).isEqualTo(403);

        var logout = send("/api/v1/auth/logout", "POST", Map.of(), csrfHeaders(csrf, csrfCookie, newRefresh));
        assertThat(logout.statusCode()).isEqualTo(204);

        HttpHeaders invalidHeaders = new HttpHeaders(); invalidHeaders.setContentType(MediaType.APPLICATION_JSON); invalidHeaders.setOrigin(ORIGIN);
        var invalidLogin = send("/api/v1/auth/login", "POST", Map.of("email", ADMIN_EMAIL, "password", "wrong-password"), invalidHeaders);
        assertThat(invalidLogin.statusCode()).isEqualTo(401);

        invalidHeaders.setOrigin("https://untrusted.example");
        var rejectedOrigin = send("/api/v1/auth/login", "POST", Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD), invalidHeaders);
        assertThat(rejectedOrigin.statusCode()).isEqualTo(403);
    }

    private HttpHeaders csrfHeaders(String csrf, String csrfCookie, String refresh) {
        HttpHeaders headers = new HttpHeaders(); headers.setOrigin(ORIGIN); headers.set("X-XSRF-TOKEN", csrf);
        headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfCookie + "; jarvis_refresh=" + refresh);
        return headers;
    }
    private HttpResponse<String> send(String path, String method, Object body, HttpHeaders headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        String payload = body == null ? "" : mapper.writeValueAsString(body);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private Map<String, Object> body(HttpResponse<String> response) throws Exception {
        return mapper.readValue(response.body(), new TypeReference<>() {});
    }
    private String cookieValue(HttpResponse<?> response, String cookieName) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(cookieName + "="))
                .map(cookie -> cookie.substring(cookieName.length() + 1, cookie.indexOf(';')))
                .findFirst().orElse("");
    }
}
