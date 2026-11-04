package com.jarvis.ai.voice.client;

import com.jarvis.ai.voice.config.OpenAIRealtimeProperties;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAIRealtimeClient {
    static final String INSTRUCTIONS = """
            Você é Jarvis, um assistente pessoal inteligente, educado, objetivo, natural e prestativo.
            Você conversa em português brasileiro. Responda de maneira natural, como em uma conversa entre duas pessoas.
            Evite respostas excessivamente longas quando estiver conversando por voz. Não invente informações.
            Quando não souber algo, informe claramente. Não afirme que executou tarefas externas quando não possui ferramentas para executá-las.
            Você ainda não possui acesso aos dados pessoais ou aos sistemas externos do usuário.
            """;

    private final RestClient restClient;
    private final OpenAIRealtimeProperties properties;

    public OpenAIRealtimeClient(RestClient restClient, OpenAIRealtimeProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public RealtimeClientSecret createClientSecret(String safetyIdentifier) {
        Map<String, Object> request = Map.of(
                "expires_after", Map.of("anchor", "created_at", "seconds", properties.clientSecretTtlSeconds()),
                "session", Map.of(
                        "type", "realtime",
                        "model", properties.model(),
                        "instructions", INSTRUCTIONS,
                        "output_modalities", List.of("audio"),
                        "audio", Map.of(
                                "input", Map.of(
                                        "noise_reduction", Map.of("type", "near_field"),
                                        "transcription", Map.of("model", "gpt-live-transcribe", "language", "pt"),
                                        "turn_detection", Map.of("type", "server_vad", "interrupt_response", true)),
                                "output", Map.of("voice", properties.voice()))));
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/client_secrets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .header("OpenAI-Safety-Identifier", safetyIdentifier)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            return parseResponse(response);
        } catch (RestClientResponseException exception) {
            throw new OpenAIRealtimeException(mapStatus(exception.getStatusCode().value()));
        } catch (ResourceAccessException exception) {
            throw new OpenAIRealtimeException(isTimeout(exception)
                    ? OpenAIRealtimeException.Kind.TIMEOUT : OpenAIRealtimeException.Kind.UNAVAILABLE, exception);
        } catch (OpenAIRealtimeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OpenAIRealtimeException(OpenAIRealtimeException.Kind.UNAVAILABLE, exception);
        }
    }

    private RealtimeClientSecret parseResponse(Map<?, ?> response) {
        if (response == null || !(response.get("value") instanceof String value) || value.isBlank()
                || !(response.get("expires_at") instanceof Number expiry)
                || !(response.get("session") instanceof Map<?, ?> session)
                || !(session.get("id") instanceof String sessionId) || sessionId.isBlank()
                || !(session.get("model") instanceof String model) || model.isBlank()) {
            throw new OpenAIRealtimeException(OpenAIRealtimeException.Kind.INVALID_RESPONSE);
        }
        return new RealtimeClientSecret(value, expiry.longValue(), sessionId, model);
    }

    private OpenAIRealtimeException.Kind mapStatus(int status) {
        if (status == 401 || status == 403) return OpenAIRealtimeException.Kind.AUTHENTICATION;
        if (status == 429) return OpenAIRealtimeException.Kind.RATE_LIMITED;
        return OpenAIRealtimeException.Kind.UNAVAILABLE;
    }

    private boolean isTimeout(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
        }
        return false;
    }
}
