package com.jarvis.ai.conversation.dto;

import java.util.List;

public record VoiceTurnResponse(
        ConversationMessageResponse persistedMessage,
        boolean created,
        List<VoiceContextMessage> recentMessages,
        List<VoiceContextMemory> activeMemories,
        VoiceContextBudget contextBudget) { }
