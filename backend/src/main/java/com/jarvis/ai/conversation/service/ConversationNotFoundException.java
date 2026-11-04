package com.jarvis.ai.conversation.service;

public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException() {
        super("Conversation was not found");
    }
}
