package com.jarvis.ai.voice.client;

public class OpenAIRealtimeException extends RuntimeException {
    public enum Kind { AUTHENTICATION, RATE_LIMITED, UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }

    private final Kind kind;

    public OpenAIRealtimeException(Kind kind) {
        super("OpenAI Realtime request failed: " + kind);
        this.kind = kind;
    }

    public OpenAIRealtimeException(Kind kind, Throwable cause) {
        super("OpenAI Realtime request failed: " + kind, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
