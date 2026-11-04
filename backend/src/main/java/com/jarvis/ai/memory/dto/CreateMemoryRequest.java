package com.jarvis.ai.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMemoryRequest(
        @NotBlank @Size(max = 50) String category,
        @NotBlank @Size(max = 20_000) String content) {
}
