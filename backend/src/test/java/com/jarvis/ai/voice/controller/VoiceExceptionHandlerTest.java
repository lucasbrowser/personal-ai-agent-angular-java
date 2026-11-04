package com.jarvis.ai.voice.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jarvis.ai.voice.client.OpenAIRealtimeException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class VoiceExceptionHandlerTest {
    @Test
    void keepsRequestIdOnProviderErrorResponseAndPreserves503() {
        var handler = new VoiceExceptionHandler();
        var original = new OpenAIRealtimeException(OpenAIRealtimeException.Kind.UNAVAILABLE,
                null, null, null, "transport_connection", new ConnectException("refused"), "request-123");

        var response = handler.providerFailure(original);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("request-123");
    }
}
