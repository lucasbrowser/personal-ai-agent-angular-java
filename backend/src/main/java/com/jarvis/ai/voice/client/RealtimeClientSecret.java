package com.jarvis.ai.voice.client;

public record RealtimeClientSecret(String value, long expiresAt, String sessionId, String model) {
    @Override
    public String toString() {
        return "RealtimeClientSecret[value=[REDACTED], expiresAt=" + expiresAt
                + ", sessionId=" + sessionId + ", model=" + model + "]";
    }
}
