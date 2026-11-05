package com.jarvis.ai.conversation.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record VoiceContextMemory(UUID id, String category, String content, BigDecimal confidence) { }
