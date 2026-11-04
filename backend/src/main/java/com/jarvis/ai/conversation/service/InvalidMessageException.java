package com.jarvis.ai.conversation.service;

public class InvalidMessageException extends RuntimeException {
    public InvalidMessageException() {
        super("Message role and source do not match");
    }
}
