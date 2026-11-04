package com.jarvis.ai.voice.controller;

import com.jarvis.ai.voice.client.OpenAIRealtimeException;
import com.jarvis.ai.voice.service.VoiceRateLimitException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = VoiceSessionController.class)
public class VoiceExceptionHandler {
    @ExceptionHandler(VoiceRateLimitException.class)
    ResponseEntity<?> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .body(error("VOICE_RATE_LIMITED", "Limite de criação de sessões de voz atingido."));
    }

    @ExceptionHandler(OpenAIRealtimeException.class)
    ResponseEntity<?> providerFailure(OpenAIRealtimeException exception) {
        ResponseEntity<?> response = switch (exception.kind()) {
            case AUTHENTICATION -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("VOICE_PROVIDER_AUTHENTICATION", "Não foi possível autenticar o serviço de voz."));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "30")
                    .body(error("VOICE_PROVIDER_RATE_LIMITED", "O serviço de voz está temporariamente ocupado."));
            case TIMEOUT -> ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(error("VOICE_PROVIDER_TIMEOUT", "O serviço de voz demorou demais para responder."));
            case INVALID_RESPONSE -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(error("VOICE_PROVIDER_INVALID_RESPONSE", "O serviço de voz retornou uma resposta inválida."));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(error("VOICE_PROVIDER_UNAVAILABLE", "O serviço de voz está indisponível."));
        };
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers -> {
                    headers.addAll(response.getHeaders());
                    if (exception.requestId() != null) headers.set("X-Request-Id", exception.requestId());
                })
                .body(response.getBody());
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
