package com.jarvis.ai.voice.client;

import com.jarvis.ai.voice.config.OpenAIRealtimeProperties;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAIRealtimeClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAIRealtimeClient.class);
    private static final Pattern SECRET = Pattern.compile("(?i)(Bearer\\s+\\S+|(?:set-cookie|cookie)\\s*[:=]\\s*[^,;\\s]+|sk-[A-Za-z0-9_-]+|ek_[A-Za-z0-9_-]+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");
    static final String INSTRUCTIONS = """
            Você é Jarvis, um assistente pessoal inteligente, educado, objetivo, natural e prestativo.
            Você conversa em português brasileiro. Responda de maneira natural, como em uma conversa entre duas pessoas.
            Evite respostas excessivamente longas quando estiver conversando por voz. Não invente informações.
            Quando não souber algo, informe claramente. Não afirme que executou tarefas externas quando não possui ferramentas para executá-las.
            Você ainda não possui acesso aos dados pessoais ou aos sistemas externos do usuário.
            """;

    private final RestClient restClient;
    private final OpenAIRealtimeProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAIRealtimeClient(RestClient restClient, OpenAIRealtimeProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public RealtimeClientSecret createClientSecret(String safetyIdentifier, String requestId) {
        try {
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
            Map<?, ?> response = restClient.post()
                    .uri("/client_secrets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .header("OpenAI-Safety-Identifier", safetyIdentifier)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            return parseResponse(response, requestId);
        } catch (RestClientResponseException exception) {
            ProviderError providerError = parseProviderError(exception.getResponseBodyAsString());
            logFailure(requestId, "provider_response", exception, exception.getStatusCode().value(),
                    providerError.code(), providerError.message());
            throw new OpenAIRealtimeException(mapStatus(exception.getStatusCode().value()),
                    exception.getStatusCode().value(), providerError.code(), providerError.message(),
                    "provider_response", exception, requestId);
        } catch (ResourceAccessException exception) {
            boolean timeout = isTimeout(exception);
            var kind = timeout
                    ? OpenAIRealtimeException.Kind.TIMEOUT : OpenAIRealtimeException.Kind.UNAVAILABLE;
            String stage = timeout ? "transport_timeout" : "transport_connection";
            logFailure(requestId, stage, exception, null, null, null);
            throw new OpenAIRealtimeException(kind, null, null, null, stage, exception, requestId);
        } catch (OpenAIRealtimeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            logFailure(requestId, "request_serialization_or_execution", exception, null, null, null);
            throw new OpenAIRealtimeException(OpenAIRealtimeException.Kind.UNAVAILABLE,
                    null, null, null, "request_serialization_or_execution", exception, requestId);
        }
    }

    private RealtimeClientSecret parseResponse(Map<?, ?> response, String requestId) {
        if (response == null || !(response.get("value") instanceof String value) || value.isBlank()
                || !(response.get("expires_at") instanceof Number expiry)
                || !(response.get("session") instanceof Map<?, ?> session)
                || !(session.get("id") instanceof String sessionId) || sessionId.isBlank()
                || !(session.get("model") instanceof String model) || model.isBlank()) {
            var exception = new OpenAIRealtimeException(OpenAIRealtimeException.Kind.INVALID_RESPONSE,
                    null, null, null, "response_validation", null, requestId);
            logFailure(requestId, "response_validation", exception, null, null, null);
            throw exception;
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

    private ProviderError parseProviderError(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            return new ProviderError(sanitize(error.path("code").asText(null), 120),
                    sanitize(error.path("message").asText(null), 500));
        } catch (Exception ignored) {
            return new ProviderError(null, null);
        }
    }

    private void logFailure(String requestId, String stage, Throwable exception, Integer status,
                            String providerCode, String providerMessage) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String exceptionType = exception.getClass().getName();
        String rootCauseType = root.getClass().getName();
        String rootCauseMessage = root instanceof RestClientResponseException
                ? null : sanitize(root.getMessage(), 500);
        log.atError()
                .addKeyValue("requestId", requestId)
                .addKeyValue("stage", stage)
                .addKeyValue("providerStatus", status)
                .addKeyValue("providerErrorCode", sanitize(providerCode, 120))
                .addKeyValue("providerErrorMessage", sanitize(providerMessage, 500))
                .addKeyValue("exceptionType", exceptionType)
                .addKeyValue("rootCauseType", rootCauseType)
                .addKeyValue("rootCauseMessage", rootCauseMessage)
                .setCause(safeStackTrace(exception))
                .log("OpenAI Realtime client-secret request failed requestId={} stage={} exceptionType={} providerStatus={} providerErrorCode={} providerErrorMessage={} rootCauseType={} rootCauseMessage={}",
                        requestId, stage, exceptionType, status, sanitize(providerCode, 120),
                        sanitize(providerMessage, 500), rootCauseType, rootCauseMessage);
    }

    private Throwable safeStackTrace(Throwable original) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable copy = copyStackTrace(original, seen);
        Throwable currentCopy = copy;
        for (Throwable cause = original.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            Throwable causeCopy = copyStackTrace(cause, seen);
            try {
                currentCopy.initCause(causeCopy);
            } catch (IllegalStateException ignored) {
                break;
            }
            currentCopy = causeCopy;
        }
        return copy;
    }

    private Throwable copyStackTrace(Throwable original, Set<Throwable> seen) {
        seen.add(original);
        Throwable copy = new Throwable(original.getClass().getName());
        copy.setStackTrace(original.getStackTrace());
        return copy;
    }

    private String sanitize(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String safe = SECRET.matcher(value).replaceAll("[REDACTED]").replaceAll("[\\r\\n\\t]", " ");
        return safe.substring(0, Math.min(safe.length(), maxLength));
    }

    private record ProviderError(String code, String message) { }
}
