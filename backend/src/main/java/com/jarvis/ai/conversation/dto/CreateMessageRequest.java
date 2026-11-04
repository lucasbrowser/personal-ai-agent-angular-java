package com.jarvis.ai.conversation.dto;

import com.jarvis.ai.conversation.domain.MessageRole;
import com.jarvis.ai.conversation.domain.MessageSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateMessageRequest(
        @NotNull MessageRole role,
        @NotBlank @Size(max = 20_000) String content,
        @NotNull MessageSource source,
        @Size(max = 200) String externalEventId) {
}
