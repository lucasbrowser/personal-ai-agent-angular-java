package com.jarvis.ai.voice.service;

public class VoiceRateLimitException extends RuntimeException {
    public VoiceRateLimitException() { super("Voice session request limit exceeded"); }
}
