package com.jarvis.ai.conversation.dto;

import com.jarvis.ai.conversation.domain.MessageRole;
import com.jarvis.ai.conversation.domain.MessageSource;
import java.time.Instant;
import java.util.UUID;

public record ConversationMessageResponse(
        UUID id,
        UUID conversationId,
        MessageRole role,
        String content,
        MessageSource source,
        String externalEventId,
        Instant createdAt) {
}
