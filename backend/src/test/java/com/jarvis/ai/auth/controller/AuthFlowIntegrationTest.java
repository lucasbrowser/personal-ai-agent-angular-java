package com.jarvis.ai.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.jarvis.ai.auth.bootstrap.AdminUserInitializer;
import com.jarvis.ai.auth.repository.UserRepository;
import com.jarvis.ai.voice.client.OpenAIRealtimeClient;
import com.jarvis.ai.voice.client.RealtimeClientSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

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
        registry.add("jarvis.openai.realtime.api-key", () -> "test-openai-api-key-not-real");
    }

    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();
    @org.springframework.beans.factory.annotation.Autowired ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired JwtEncoder jwtEncoder;
    @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired AdminUserInitializer adminInitializer;
    @org.springframework.beans.factory.annotation.Autowired UserRepository users;
    @MockitoBean OpenAIRealtimeClient realtimeClient;

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
        String refreshSetCookie = setCookie(login, "jarvis_refresh");
        assertThat(refreshSetCookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth").doesNotContain("Secure");

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

    @Test
    void rejectsExpiredBadSignatureMalformedAndInvalidClaimJwt() throws Exception {
        var valid = jwt("jarvis", Instant.now().plusSeconds(300), true);
        String[] segments = valid.split("\\.");
        char replacement = segments[2].charAt(0) == 'A' ? 'B' : 'A';
        String badSignature = segments[0] + "." + segments[1] + "." + replacement + segments[2].substring(1);
        String expired = jwt("jarvis", Instant.now().minusSeconds(30), true);
        String wrongIssuer = jwt("untrusted-issuer", Instant.now().plusSeconds(300), true);
        String missingClaims = jwt("jarvis", Instant.now().plusSeconds(300), false);

        assertUnauthorized(expired);
        assertUnauthorized(badSignature);
        assertUnauthorized("not-a-jwt");
        assertUnauthorized(wrongIssuer);
        assertUnauthorized(missingClaims);
    }

    @Test
    void rejectsExpiredAndRevokedRefreshTokensAndPersistsOnlyHashes() throws Exception {
        UUID userId = users.findByEmail(ADMIN_EMAIL).orElseThrow().id();
        Instant now = Instant.now();
        String expired = "known-expired-refresh-value";
        String revoked = "known-revoked-refresh-value";
        jdbc.update("insert into jarvis.refresh_tokens(id,user_id,token_hash,expires_at) values(?,?,?,?)",
                UUID.randomUUID(), userId, sha256(expired), Timestamp.from(now.minusSeconds(1)));
        jdbc.update("insert into jarvis.refresh_tokens(id,user_id,token_hash,expires_at,revoked_at) values(?,?,?,?,?)",
                UUID.randomUUID(), userId, sha256(revoked), Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now));
        assertThat(jdbc.queryForObject("select count(*) from jarvis.refresh_tokens where token_hash in (?,?)", Integer.class, sha256(expired), sha256(revoked))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from jarvis.refresh_tokens where token_hash in (?,?)", Integer.class, expired, revoked)).isZero();

        var csrf = csrfSession();
        assertThat(refresh(expired, csrf).statusCode()).isEqualTo(401);
        assertThat(refresh(revoked, csrf).statusCode()).isEqualTo(401);
    }

    @Test
    void acceptsOnlyOneOfTwoConcurrentRefreshRequests() throws Exception {
        var csrf = csrfSession();
        HttpHeaders loginHeaders = new HttpHeaders(); loginHeaders.setContentType(MediaType.APPLICATION_JSON); loginHeaders.setOrigin(ORIGIN);
        var login = send("/api/v1/auth/login", "POST", Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD), loginHeaders);
        assertThat(login.statusCode()).isEqualTo(200);
        String refreshToken = cookieValue(login, "jarvis_refresh");
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> concurrentRefresh(start, csrf, refreshToken));
        var second = CompletableFuture.supplyAsync(() -> concurrentRefresh(start, csrf, refreshToken));
        start.countDown();
        int firstStatus = first.get(15, TimeUnit.SECONDS);
        int secondStatus = second.get(15, TimeUnit.SECONDS);
        assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void adminBootstrapIsIdempotentAndDoesNotReplaceExistingPassword() throws Exception {
        String before = jdbc.queryForObject("select password_hash from jarvis.users where email=?", String.class, ADMIN_EMAIL);
        adminInitializer.run(new DefaultApplicationArguments(new String[0]));
        adminInitializer.run(new DefaultApplicationArguments(new String[0]));
        String after = jdbc.queryForObject("select password_hash from jarvis.users where email=?", String.class, ADMIN_EMAIL);
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from jarvis.users where email=?", Integer.class, ADMIN_EMAIL)).isEqualTo(1);
    }

    @Test
    void disabledUserCannotLogInOrRefresh() throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.setOrigin(ORIGIN);
        var session = send("/api/v1/auth/login", "POST", Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD), headers);
        String refreshToken = cookieValue(session, "jarvis_refresh");
        var csrf = csrfSession();
        jdbc.update("update jarvis.users set enabled=false where email=?", ADMIN_EMAIL);
        try {
            var login = send("/api/v1/auth/login", "POST", Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD), headers);
            assertThat(login.statusCode()).isEqualTo(401);
            assertThat(refresh(refreshToken, csrf).statusCode()).isEqualTo(401);
        } finally {
            jdbc.update("update jarvis.users set enabled=true where email=?", ADMIN_EMAIL);
        }
    }

    @Test
    void voiceSessionRequiresAuthenticationAndUsesAuthenticatedSubjectWithoutCachingSecret() throws Exception {
        UUID authenticatedUser = users.findByEmail(ADMIN_EMAIL).orElseThrow().id();
        given(realtimeClient.createClientSecret(anyString())).willReturn(
                new RealtimeClientSecret("ek_ephemeral-only", 1_800_000_000L, "sess_voice", "gpt-realtime-2.1-mini"));

        var unauthorized = send("/api/v1/voice/sessions", "POST", Map.of(), new HttpHeaders());
        assertThat(unauthorized.statusCode()).isEqualTo(401);

        UUID forgedUser = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtForSubject(authenticatedUser));
        var response = send("/api/v1/voice/sessions", "POST", Map.of("userId", forgedUser), headers);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.body()).contains("ek_ephemeral-only", "sess_voice", "gpt-realtime-2.1-mini")
                .doesNotContain("test-openai-api-key-not-real");
        String expectedSafetyId = "sha256:" + sha256("jarvis-realtime-user:" + authenticatedUser);
        verify(realtimeClient).createClientSecret(expectedSafetyId);
    }

    @Test
    void voiceSessionCreationIsRateLimitedPerAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();
        given(realtimeClient.createClientSecret(anyString())).willReturn(
                new RealtimeClientSecret("ek_ephemeral-only", 1_800_000_000L, "sess_voice", "gpt-realtime-2.1-mini"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtForSubject(userId));

        for (int call = 0; call < 5; call++) {
            assertThat(send("/api/v1/voice/sessions", "POST", Map.of(), headers).statusCode()).isEqualTo(200);
        }
        var limited = send("/api/v1/voice/sessions", "POST", Map.of(), headers);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After").orElse("")).isEqualTo("60");
    }

    private HttpHeaders csrfHeaders(String csrf, String csrfCookie, String refresh) {
        HttpHeaders headers = new HttpHeaders(); headers.setOrigin(ORIGIN); headers.set("X-XSRF-TOKEN", csrf);
        headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrfCookie + "; jarvis_refresh=" + refresh);
        return headers;
    }
    private record CsrfSession(String token, String cookie) { }
    private CsrfSession csrfSession() throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.setOrigin(ORIGIN);
        var response = send("/api/v1/auth/csrf", "GET", null, headers);
        return new CsrfSession(body(response).get("token").toString(), cookieValue(response, "XSRF-TOKEN"));
    }
    private HttpResponse<String> refresh(String raw, CsrfSession csrf) throws Exception {
        return send("/api/v1/auth/refresh", "POST", Map.of(), csrfHeaders(csrf.token(), csrf.cookie(), raw));
    }
    private int concurrentRefresh(CountDownLatch start, CsrfSession csrf, String token) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) return 0;
            return refresh(token, csrf).statusCode();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private void assertUnauthorized(String token) throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token);
        assertThat(send("/api/v1/auth/me", "GET", null, headers).statusCode()).isEqualTo(401);
    }
    private String jwt(String issuer, Instant expiresAt, boolean includeProfile) {
        return jwt(issuer, expiresAt, includeProfile, UUID.randomUUID().toString());
    }
    private String jwtForSubject(UUID subject) {
        return jwt("jarvis", Instant.now().plusSeconds(300), true, subject.toString());
    }
    private String jwt(String issuer, Instant expiresAt, boolean includeProfile, String subject) {
        Instant now = Instant.now();
        Instant issuedAt = expiresAt.isBefore(now) ? expiresAt.minusSeconds(300) : now;
        var claims = JwtClaimsSet.builder().issuer(issuer).issuedAt(issuedAt).expiresAt(expiresAt)
                .subject(subject).claim("roles", List.of("ADMIN"));
        if (includeProfile) claims.claim("email", ADMIN_EMAIL).claim("name", "Test Admin");
        return jwtEncoder.encode(JwtEncoderParameters.from(org.springframework.security.oauth2.jwt.JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
    private String setCookie(HttpResponse<?> response, String cookieName) {
        return response.headers().allValues("Set-Cookie").stream().filter(cookie -> cookie.startsWith(cookieName + "="))
                .findFirst().orElse("");
    }
}
