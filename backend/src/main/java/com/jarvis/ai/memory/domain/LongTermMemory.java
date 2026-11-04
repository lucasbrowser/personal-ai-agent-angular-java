package com.jarvis.ai.memory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LongTermMemory(
        UUID id,
        UUID userId,
        String category,
        String content,
        MemoryStatus status,
        BigDecimal confidence,
        MemorySourceType sourceType,
        UUID sourceConversationId,
        Instant createdAt,
        Instant updatedAt,
        Instant lastUsedAt) {
}
