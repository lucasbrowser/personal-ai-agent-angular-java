package com.jarvis.ai.voice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jarvis.ai.voice.config.OpenAIRealtimeProperties;
import tools.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.time.Duration;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class OpenAIRealtimeClientTest {
    private static final String ENDPOINT = "https://api.openai.com/v1/realtime/client_secrets";
    private static final String SAFETY_IDENTIFIER = "a".repeat(64);
    private static final String RESPONSE = """
            {"value":"ek_temporary","expires_at":1800000000,"session":{"id":"sess_test","model":"gpt-realtime-2.1-mini"}}
            """;

    @Test
    void createsGaClientSecretWithConfiguredSessionAndHashedUserIdentifier() {
        var fixture = fixture("configured-test-key", "gpt-realtime-2.1-mini", "marin", 600);
        fixture.server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer configured-test-key"))
                .andExpect(header("OpenAI-Safety-Identifier", SAFETY_IDENTIFIER))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"seconds\":600"),
                        org.hamcrest.Matchers.containsString("\"type\":\"realtime\""),
                        org.hamcrest.Matchers.containsString("\"model\":\"gpt-realtime-2.1-mini\""),
                        org.hamcrest.Matchers.containsString("\"voice\":\"marin\""),
                        org.hamcrest.Matchers.containsString("dados de referência não confiáveis"),
                        org.hamcrest.Matchers.containsString("\"noise_reduction\":{\"type\":\"near_field\"}"),
                        org.hamcrest.Matchers.containsString("\"type\":\"server_vad\""),
                        org.hamcrest.Matchers.containsString("\"create_response\":false"),
                        org.hamcrest.Matchers.containsString("\"model\":\"gpt-live-transcribe\""),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("configured-test-key")))))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        RealtimeClientSecret result = fixture.client.createClientSecret(SAFETY_IDENTIFIER, "request-123");

        assertThat(result).isEqualTo(new RealtimeClientSecret("ek_temporary", 1_800_000_000L,
                "sess_test", "gpt-realtime-2.1-mini"));
        assertThat(result.value()).doesNotContain("configured-test-key");
        assertThat(result.toString()).doesNotContain("ek_temporary");
        fixture.server.verify();
    }

    @Test
    void mapsProviderAuthenticationRateLimitAndServerErrorsWithoutLeakingBodies() {
        assertProviderStatus(HttpStatus.BAD_REQUEST, OpenAIRealtimeException.Kind.UNAVAILABLE);
        assertProviderStatus(HttpStatus.UNAUTHORIZED, OpenAIRealtimeException.Kind.AUTHENTICATION);
        assertProviderStatus(HttpStatus.FORBIDDEN, OpenAIRealtimeException.Kind.AUTHENTICATION);
        assertProviderStatus(HttpStatus.TOO_MANY_REQUESTS, OpenAIRealtimeException.Kind.RATE_LIMITED);
        assertProviderStatus(HttpStatus.INTERNAL_SERVER_ERROR, OpenAIRealtimeException.Kind.UNAVAILABLE);
    }

    @Test
    void mapsTimeoutAndRejectsMalformedProviderResponse() {
        var timeout = fixture("test-key", "gpt-realtime-2.1-mini", "marin", 600);
        timeout.server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException("timeout"));
        });
        assertThatThrownBy(() -> timeout.client.createClientSecret(SAFETY_IDENTIFIER, "request-timeout"))
                .isInstanceOfSatisfying(OpenAIRealtimeException.class,
                        exception -> {
                            assertThat(exception.kind()).isEqualTo(OpenAIRealtimeException.Kind.TIMEOUT);
                            assertThat(exception.stage()).isEqualTo("transport_timeout");
                            assertThat(exception.requestId()).isEqualTo("request-timeout");
                            assertThat(exception.getCause()).isInstanceOf(ResourceAccessException.class);
                        });

        var connection = fixture("test-key", "gpt-realtime-2.1-mini", "marin", 600);
        connection.server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new ResourceAccessException("connection refused", new ConnectException("connection refused"));
        });
        assertThatThrownBy(() -> connection.client.createClientSecret(SAFETY_IDENTIFIER, "request-connection"))
                .isInstanceOfSatisfying(OpenAIRealtimeException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(OpenAIRealtimeException.Kind.UNAVAILABLE);
                    assertThat(exception.stage()).isEqualTo("transport_connection");
                    assertThat(exception.requestId()).isEqualTo("request-connection");
                    assertThat(exception.getCause()).isInstanceOf(ResourceAccessException.class);
                });

        var invalid = fixture("test-key", "gpt-realtime-2.1-mini", "marin", 600);
        invalid.server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"value\":\"\",\"expires_at\":1,\"session\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> invalid.client.createClientSecret(SAFETY_IDENTIFIER, "request-invalid"))
                .isInstanceOfSatisfying(OpenAIRealtimeException.class, exception -> {
                    assertThat(exception.kind()).isEqualTo(OpenAIRealtimeException.Kind.INVALID_RESPONSE);
                    assertThat(exception.requestId()).isEqualTo("request-invalid");
                    assertThat(exception.stage()).isEqualTo("response_validation");
                });
    }

    @Test
    void classifiesRequestSerializationFailureAndPreservesItsCause() {
        var fixture = fixture("test-key", null, "marin", 600);

        assertThatThrownBy(() -> fixture.client.createClientSecret(SAFETY_IDENTIFIER, "request-serialization"))
                .isInstanceOfSatisfying(OpenAIRealtimeException.class, exception -> {
                    assertThat(exception.stage()).isEqualTo("request_serialization_or_execution");
                    assertThat(exception.requestId()).isEqualTo("request-serialization");
                    assertThat(exception.getCause()).isInstanceOf(NullPointerException.class);
                });
    }

    @Test
    void rejectsInvalidSafetyIdentifiersBeforeSendingTheRequest() {
        var fixture = fixture("test-key", "gpt-realtime-2.1-mini", "marin", 600);

        assertThatThrownBy(() -> fixture.client.createClientSecret(null, "request-null-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.client.createClientSecret("", "request-empty-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.client.createClientSecret("b".repeat(65), "request-long-id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.client.createClientSecret("not-a-hex-hash", "request-unhashed-id"))
                .isInstanceOf(IllegalArgumentException.class);
        fixture.server.verify();
    }

    @Test
    void consoleLogContainsFieldsAndSafeOriginalStackWithoutSecrets() {
        var fixture = fixture("test-api-secret", "gpt-realtime-2.1-mini", "marin", 600);
        fixture.server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"rejected ek_provider-secret\"}}"));

        Logger logger = (Logger) LoggerFactory.getLogger(OpenAIRealtimeClient.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> fixture.client.createClientSecret(SAFETY_IDENTIFIER, "request-log-test"))
                    .isInstanceOf(OpenAIRealtimeException.class);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        ILoggingEvent event = appender.list.getFirst();
        String exceptionTrace = event.getThrowableProxy().getMessage() + " "
                + java.util.Arrays.toString(event.getThrowableProxy().getStackTraceElementProxyArray());
        assertThat(event.getFormattedMessage())
                .contains("requestId=request-log-test", "stage=provider_response", "providerStatus=401",
                        "providerErrorCode=invalid_api_key", "providerErrorMessage=rejected [REDACTED]",
                        "exceptionType=org.springframework.web.client.HttpClientErrorException$Unauthorized",
                        "rootCauseType=org.springframework.web.client.HttpClientErrorException$Unauthorized");
        assertThat(exceptionTrace).doesNotContain("ek_provider-secret", "test-api-secret");
        assertThat(event.getFormattedMessage()).doesNotContain(SAFETY_IDENTIFIER);
        assertThat(event.getThrowableProxy()).isNotNull();
    }

    private void assertProviderStatus(HttpStatus status, OpenAIRealtimeException.Kind expected) {
        var fixture = fixture("test-key", "gpt-realtime-2.1-mini", "marin", 600);
        fixture.server.expect(requestTo(ENDPOINT)).andRespond(withStatus(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"provider_error\",\"message\":\"diagnostic ek_provider-secret\"}}"));
        assertThatThrownBy(() -> fixture.client.createClientSecret(SAFETY_IDENTIFIER, "request-provider"))
                .isInstanceOfSatisfying(OpenAIRealtimeException.class,
                        exception -> {
                            assertThat(exception.kind()).isEqualTo(expected);
                            assertThat(exception.providerStatus()).isEqualTo(status.value());
                            assertThat(exception.providerCode()).isEqualTo("provider_error");
                            assertThat(exception.providerMessage()).isEqualTo("diagnostic [REDACTED]");
                            assertThat(exception.requestId()).isEqualTo("request-provider");
                            assertThat(exception.getCause()).isNotNull();
                        });
        fixture.server.verify();
    }

    private Fixture fixture(String key, String model, String voice, int ttl) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1/realtime");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAIRealtimeProperties(key, model, voice, ttl, Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new Fixture(new OpenAIRealtimeClient(builder.build(), properties, new ObjectMapper()), server);
    }

    private record Fixture(OpenAIRealtimeClient client, MockRestServiceServer server) { }
}
