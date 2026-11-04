package com.jarvis.ai.voice.client;

public class OpenAIRealtimeException extends RuntimeException {
    public enum Kind { AUTHENTICATION, RATE_LIMITED, UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }

    private final Kind kind;
    private final Integer providerStatus;
    private final String providerCode;
    private final String providerMessage;
    private final String stage;
    private final String requestId;

    public OpenAIRealtimeException(Kind kind) {
        this(kind, null, null, null, "response_validation", null, null);
    }

    public OpenAIRealtimeException(Kind kind, Throwable cause) {
        this(kind, null, null, null, "transport", cause, null);
    }

    public OpenAIRealtimeException(Kind kind, Integer providerStatus, String providerCode,
                                   String providerMessage, String stage, Throwable cause, String requestId) {
        super("OpenAI Realtime request failed: " + kind, cause);
        this.kind = kind;
        this.providerStatus = providerStatus;
        this.providerCode = providerCode;
        this.providerMessage = providerMessage;
        this.stage = stage;
        this.requestId = requestId;
    }

    public Kind kind() { return kind; }
    public Integer providerStatus() { return providerStatus; }
    public String providerCode() { return providerCode; }
    public String providerMessage() { return providerMessage; }
    public String stage() { return stage; }
    public String requestId() { return requestId; }
}
