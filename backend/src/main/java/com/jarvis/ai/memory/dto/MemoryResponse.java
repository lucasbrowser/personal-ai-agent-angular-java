package com.jarvis.ai.memory.dto;

import com.jarvis.ai.memory.domain.MemorySourceType;
import com.jarvis.ai.memory.domain.MemoryStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MemoryResponse(
        UUID id,
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
