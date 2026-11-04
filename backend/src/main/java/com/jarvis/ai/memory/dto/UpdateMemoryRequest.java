package com.jarvis.ai.memory.dto;

import com.jarvis.ai.memory.domain.MemoryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMemoryRequest(
        @NotBlank @Size(max = 50) String category,
        @NotBlank @Size(max = 20_000) String content,
        @NotNull MemoryStatus status) {
}
