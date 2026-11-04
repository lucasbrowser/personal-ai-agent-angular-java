package com.jarvis.ai.conversation.dto;

import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @Size(max = 200, message = "O título deve ter no máximo 200 caracteres") String title) {
}
