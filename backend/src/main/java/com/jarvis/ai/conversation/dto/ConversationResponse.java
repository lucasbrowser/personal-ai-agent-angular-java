package com.jarvis.ai.conversation.dto;

import com.jarvis.ai.conversation.domain.ConversationStatus;
import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        String title,
        ConversationStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant lastMessageAt) {
}
