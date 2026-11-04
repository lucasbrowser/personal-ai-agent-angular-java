package com.jarvis.ai.memory.service;

import com.jarvis.ai.conversation.dto.PageResponse;
import com.jarvis.ai.memory.domain.LongTermMemory;
import com.jarvis.ai.memory.domain.MemorySourceType;
import com.jarvis.ai.memory.domain.MemoryStatus;
import com.jarvis.ai.memory.dto.CreateMemoryRequest;
import com.jarvis.ai.memory.dto.MemoryResponse;
import com.jarvis.ai.memory.dto.UpdateMemoryRequest;
import com.jarvis.ai.memory.repository.LongTermMemoryRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryService {
    private final LongTermMemoryRepository memories;

    public MemoryService(LongTermMemoryRepository memories) {
        this.memories = memories;
    }

    public MemoryResponse create(UUID authenticatedUserId, CreateMemoryRequest request) {
        Instant now = Instant.now();
        LongTermMemory memory = new LongTermMemory(UUID.randomUUID(), authenticatedUserId,
                request.category().trim(), request.content(), MemoryStatus.ACTIVE, null,
                MemorySourceType.USER_PROVIDED, null, now, now, null);
        memories.save(memory);
        return response(memory);
    }

    public PageResponse<MemoryResponse> list(
            UUID authenticatedUserId, int page, int size, String category, MemoryStatus status) {
        String normalizedCategory = category == null ? null : category.trim();
        long total = memories.countByUserId(authenticatedUserId, normalizedCategory, status);
        var items = memories.findPageByUserId(authenticatedUserId, normalizedCategory, status, size, offset(page, size))
                .stream().map(MemoryService::response).toList();
        return PageResponse.of(items, page, size, total);
    }

    public MemoryResponse get(UUID id, UUID authenticatedUserId) {
        return memories.findByIdAndUserId(id, authenticatedUserId)
                .map(MemoryService::response)
                .orElseThrow(MemoryNotFoundException::new);
    }

    @Transactional
    public MemoryResponse update(UUID id, UUID authenticatedUserId, UpdateMemoryRequest request) {
        if (memories.findByIdAndUserId(id, authenticatedUserId).isEmpty()) throw new MemoryNotFoundException();
        boolean updated = memories.updateOwnedFields(id, authenticatedUserId, request.category().trim(),
                request.content(), request.status(), Instant.now());
        if (!updated) throw new MemoryNotFoundException();
        return memories.findByIdAndUserId(id, authenticatedUserId)
                .map(MemoryService::response)
                .orElseThrow(MemoryNotFoundException::new);
    }

    public void delete(UUID id, UUID authenticatedUserId) {
        if (!memories.deleteByIdAndUserId(id, authenticatedUserId)) throw new MemoryNotFoundException();
    }

    private static long offset(int page, int size) {
        return (long) page * size;
    }

    private static MemoryResponse response(LongTermMemory memory) {
        return new MemoryResponse(memory.id(), memory.category(), memory.content(), memory.status(),
                memory.confidence(), memory.sourceType(), memory.sourceConversationId(), memory.createdAt(),
                memory.updatedAt(), memory.lastUsedAt());
    }
}
