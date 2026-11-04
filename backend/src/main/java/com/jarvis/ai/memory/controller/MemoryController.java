package com.jarvis.ai.memory.controller;

import com.jarvis.ai.conversation.dto.PageResponse;
import com.jarvis.ai.memory.domain.MemoryStatus;
import com.jarvis.ai.memory.dto.CreateMemoryRequest;
import com.jarvis.ai.memory.dto.MemoryResponse;
import com.jarvis.ai.memory.dto.UpdateMemoryRequest;
import com.jarvis.ai.memory.service.MemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {
    private static final int MAX_PAGE_SIZE = 100;
    private final MemoryService memories;

    public MemoryController(MemoryService memories) {
        this.memories = memories;
    }

    @PostMapping
    public ResponseEntity<MemoryResponse> create(
            @AuthenticationPrincipal Jwt principal, @Valid @RequestBody CreateMemoryRequest request) {
        MemoryResponse created = memories.create(userId(principal), request);
        return ResponseEntity.created(URI.create("/api/v1/memories/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<MemoryResponse> list(
            @AuthenticationPrincipal Jwt principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) @jakarta.validation.constraints.Size(max = 50) String category,
            @RequestParam(required = false) MemoryStatus status) {
        return memories.list(userId(principal), page, size, category, status);
    }

    @GetMapping("/{id}")
    public MemoryResponse get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
        return memories.get(id, userId(principal));
    }

    @PutMapping("/{id}")
    public MemoryResponse update(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateMemoryRequest request) {
        return memories.update(id, userId(principal), request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt principal, @PathVariable UUID id) {
        memories.delete(id, userId(principal));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }
}
