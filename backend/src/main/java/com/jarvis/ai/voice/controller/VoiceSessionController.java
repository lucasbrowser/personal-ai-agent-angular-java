package com.jarvis.ai.voice.controller;

import com.jarvis.ai.voice.client.RealtimeClientSecret;
import com.jarvis.ai.voice.dto.CreateVoiceSessionRequest;
import com.jarvis.ai.voice.service.VoiceSessionService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/voice")
public class VoiceSessionController {
    private final VoiceSessionService sessions;

    public VoiceSessionController(VoiceSessionService sessions) { this.sessions = sessions; }

    @PostMapping("/sessions")
    public ResponseEntity<RealtimeClientSecret> create(
            @AuthenticationPrincipal Jwt principal, @Valid @RequestBody CreateVoiceSessionRequest request) {
        UUID userId = UUID.fromString(principal.getSubject());
        String requestId = UUID.randomUUID().toString();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Request-Id", requestId)
                .body(sessions.create(userId, request.conversationId(), requestId));
    }
}
