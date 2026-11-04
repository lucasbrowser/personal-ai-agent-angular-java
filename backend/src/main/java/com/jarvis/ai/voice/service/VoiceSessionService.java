package com.jarvis.ai.voice.service;

import com.jarvis.ai.voice.client.OpenAIRealtimeClient;
import com.jarvis.ai.voice.client.RealtimeClientSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VoiceSessionService {
    private final OpenAIRealtimeClient client;
    private final VoiceSessionRateLimiter rateLimiter;

    public VoiceSessionService(OpenAIRealtimeClient client, VoiceSessionRateLimiter rateLimiter) {
        this.client = client;
        this.rateLimiter = rateLimiter;
    }

    public RealtimeClientSecret create(UUID authenticatedUserId, String requestId) {
        if (!rateLimiter.allow(authenticatedUserId)) throw new VoiceRateLimitException();
        return client.createClientSecret(safetyIdentifier(authenticatedUserId), requestId);
    }

    static String safetyIdentifier(UUID userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("jarvis-realtime-user:" + userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
