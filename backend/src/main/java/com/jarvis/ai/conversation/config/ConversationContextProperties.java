package com.jarvis.ai.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jarvis.conversation.context")
public class ConversationContextProperties {
    private int maxRecentMessages = 20;
    private int maxMemories = 10;
    private int maxCharacters = 12_000;

    public int getMaxRecentMessages() { return maxRecentMessages; }
    public void setMaxRecentMessages(int maxRecentMessages) { this.maxRecentMessages = maxRecentMessages; }
    public int getMaxMemories() { return maxMemories; }
    public void setMaxMemories(int maxMemories) { this.maxMemories = maxMemories; }
    public int getMaxCharacters() { return maxCharacters; }
    public void setMaxCharacters(int maxCharacters) { this.maxCharacters = maxCharacters; }
}
