package com.jarvis.ai.conversation.service;

import com.jarvis.ai.conversation.config.ConversationContextProperties;
import com.jarvis.ai.conversation.domain.ConversationMessage;
import com.jarvis.ai.conversation.dto.VoiceContextBudget;
import com.jarvis.ai.conversation.dto.VoiceContextMemory;
import com.jarvis.ai.conversation.dto.VoiceContextMessage;
import com.jarvis.ai.conversation.repository.ConversationMessageRepository;
import com.jarvis.ai.memory.domain.LongTermMemory;
import com.jarvis.ai.memory.repository.LongTermMemoryRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VoiceContextSelectionService {
    private static final String ESTIMATION = "caracteres Java de conteúdo e rótulos; aproximação, não contagem de tokens";
    private static final int MAX_MESSAGES_CONFIG = 100;
    private static final int MAX_MEMORIES_CONFIG = 100;
    private static final int MAX_CHARACTERS_CONFIG = 100_000;

    private final ConversationMessageRepository messages;
    private final LongTermMemoryRepository memories;
    private final ConversationContextProperties properties;

    public VoiceContextSelectionService(ConversationMessageRepository messages,
                                        LongTermMemoryRepository memories,
                                        ConversationContextProperties properties) {
        this.messages = messages;
        this.memories = memories;
        this.properties = properties;
        if (properties.getMaxRecentMessages() < 1 || properties.getMaxRecentMessages() > MAX_MESSAGES_CONFIG
                || properties.getMaxMemories() < 0 || properties.getMaxMemories() > MAX_MEMORIES_CONFIG
                || properties.getMaxCharacters() < 1 || properties.getMaxCharacters() > MAX_CHARACTERS_CONFIG) {
            throw new IllegalArgumentException("Invalid conversation context limits");
        }
    }

    public Selection select(UUID conversationId, UUID authenticatedUserId) {
        int maxChars = properties.getMaxCharacters();
        int messageBudget = (int) Math.ceil(maxChars * 0.75d);
        int memoryBudget = maxChars - messageBudget;

        List<ConversationMessage> recentCandidates = messages.findRecentByConversationAndUserId(
                conversationId, authenticatedUserId, properties.getMaxRecentMessages());
        List<VoiceContextMessage> selectedMessages = selectMessages(recentCandidates, messageBudget);
        int messageChars = selectedMessages.stream()
                .mapToInt(message -> message.content().length() + message.role().name().length() + 2).sum();

        List<LongTermMemory> memoryCandidates = memories.findActiveContextCandidatesByUserId(
                authenticatedUserId, properties.getMaxMemories());
        List<VoiceContextMemory> selectedMemories = selectMemories(memoryCandidates, memoryBudget);
        int memoryChars = selectedMemories.stream()
                .mapToInt(memory -> memory.content().length() + memory.category().length() + 2).sum();

        return new Selection(selectedMessages, selectedMemories,
                new VoiceContextBudget(maxChars, messageChars + memoryChars, messageChars, memoryChars, ESTIMATION));
    }

    private static List<VoiceContextMessage> selectMessages(List<ConversationMessage> candidates, int budget) {
        List<VoiceContextMessage> selected = new ArrayList<>();
        int used = 0;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            ConversationMessage candidate = candidates.get(i);
            int chars = candidate.content().length() + candidate.role().name().length() + 2;
            if (chars <= budget - used) {
                selected.add(new VoiceContextMessage(candidate.id(), candidate.role(), candidate.content(),
                        candidate.createdAt()));
                used += chars;
            }
        }
        Collections.reverse(selected);
        return List.copyOf(selected);
    }

    private static List<VoiceContextMemory> selectMemories(List<LongTermMemory> candidates, int budget) {
        List<VoiceContextMemory> selected = new ArrayList<>();
        int used = 0;
        for (LongTermMemory candidate : candidates) {
            int chars = candidate.content().length() + candidate.category().length() + 2;
            if (chars <= budget - used) {
                selected.add(new VoiceContextMemory(candidate.id(), candidate.category(), candidate.content(),
                        candidate.confidence()));
                used += chars;
            }
        }
        return List.copyOf(selected);
    }

    public record Selection(List<VoiceContextMessage> recentMessages, List<VoiceContextMemory> activeMemories,
                            VoiceContextBudget budget) { }
}
