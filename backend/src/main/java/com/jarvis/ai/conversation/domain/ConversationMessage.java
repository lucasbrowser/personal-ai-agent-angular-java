package com.jarvis.ai.conversation.domain;

import java.time.Instant;
import java.util.UUID;

public record ConversationMessage(
        UUID id,
        UUID conversationId,
        UUID userId,
        MessageRole role,
        String content,
        MessageSource source,
        String externalEventId,
        Instant createdAt) {
}
