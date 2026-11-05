package com.jarvis.ai.conversation.dto;

import com.jarvis.ai.conversation.domain.MessageRole;
import java.time.Instant;
import java.util.UUID;

public record VoiceContextMessage(UUID id, MessageRole role, String content, Instant createdAt) { }
