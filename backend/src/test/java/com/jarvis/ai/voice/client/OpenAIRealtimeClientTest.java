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
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class OpenAIRealtimeClientTest {
    private static final String ENDPOINT = "https://api.openai.com/v1/realtime/client_secrets";
    private static final String RESPONSE = """
            {"value":"ek_temporary","expires_at":1800000000,"session":{"id":"sess_test","model":"gpt-realtime-2.1-mini"}}
            """;

    @Test
    void createsGaClientSecretWithConfiguredSessionAndHashedUserIdentifier() {
        var fixture = fixture("configured-test-key", "gpt-realtime-2.1-mini", "marin", 600);
        fixture.server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer configured-test-key"))
                .andExpect(header("OpenAI-Safety-Identifier", "sha256:stable-user-hash"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"seconds\":600"),
                        org.hamcrest.Matchers.containsString("\"type\":\"realtime\""),
                        org.hamcrest.Matchers.containsString("\"model\":\"gpt-realtime-2.1-mini\""),
                        org.hamcrest.Matchers.containsString("\"voice\":\"marin\""),
                        org.hamcrest.Matchers.containsString("\"noise_reduction\":{\"type\":\"near_field\"}"),
                        org.hamcrest.Matchers.containsString("\"type\":\"server_vad\""),
                        org.hamcrest.Matchers.containsString("\"model\":\"gpt-live-transcribe\""),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("configured-test-key")))))
                .andRespond(withSuccess(RESPONSE, MediaType.APPLICATION_JSON));

        RealtimeClientSecret result = fixture.client.createClientSecret("sha256:stable-user-hash");

        assertThat(result).isEqualTo(new RealtimeClientSecret("ek_temporary", 1_800_000_000L,
                "sess_test", "gpt-realtime-2.1-mini"));
        assertThat(result.value()).doesNotContain("configured-test-key");
        assertThat(result.toString()).doesNotContain("ek_temporary");
        fixture.server.verify();
    }

    @Test
    void mapsProviderAuthenticationRateLimitAndServerErrorsWithoutLeakingBodies() {
        assertProviderStatus(HttpStatus.UNAUTHORIZED, OpenAIRealtimeException.Kind.AUTHENTICATION);
        assertProviderStatus(HttpStatus.TOO_MANY_REQUESTS, OpenAIRealtimeException.Kind.RATE_LIMITED);
        assertProviderStatus(HttpStatus.INTERNAL_SERVER_ERROR, OpenAIRealtimeException.Kind.UNAVAILABLE);
    }

    @Test
    void mapsTimeoutAndRejectsMalformedProviderResponse() {
        var timeout = fixture("test-key", "gpt-realtime-2.1-mini", "marin", 600);
        timeout.server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException("timeout"));
        });
        assertThatThrownBy(() -> timeout.client.createClientSecret("safe-id"))
                .isInstanceOfSatisfying(OpenAIRealtimeException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(OpenAIRealtimeException.Kind.TIMEOUT));

        var invalid = fixture("test-key", "gpt-realtime-2.1-mini", "marin", 600);
        invalid.server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"value\":\"\",\"expires_at\":1,\"session\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> invalid.client.createClientSecret("safe-id"))
                .isInstanceOfSatisfying(OpenAIRealtimeException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(OpenAIRealtimeException.Kind.INVALID_RESPONSE));
    }

    private void assertProviderStatus(HttpStatus status, OpenAIRealtimeException.Kind expected) {
        var fixture = fixture("test-key", "gpt-realtime-2.1-mini", "marin", 600);
        fixture.server.expect(requestTo(ENDPOINT)).andRespond(withStatus(status)
                .contentType(MediaType.APPLICATION_JSON).body("sensitive-provider-error-body"));
        assertThatThrownBy(() -> fixture.client.createClientSecret("safe-id"))
                .isInstanceOfSatisfying(OpenAIRealtimeException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(expected));
        fixture.server.verify();
    }

    private Fixture fixture(String key, String model, String voice, int ttl) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1/realtime");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAIRealtimeProperties(key, model, voice, ttl, Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new Fixture(new OpenAIRealtimeClient(builder.build(), properties), server);
    }

    private record Fixture(OpenAIRealtimeClient client, MockRestServiceServer server) { }
}
