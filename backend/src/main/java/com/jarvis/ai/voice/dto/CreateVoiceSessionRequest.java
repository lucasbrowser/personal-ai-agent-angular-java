package com.jarvis.ai.voice.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateVoiceSessionRequest(@NotNull UUID conversationId) { }
