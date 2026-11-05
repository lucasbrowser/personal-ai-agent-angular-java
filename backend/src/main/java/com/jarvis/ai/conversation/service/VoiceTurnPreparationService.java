package com.jarvis.ai.conversation.service;

import com.jarvis.ai.conversation.dto.PrepareVoiceTurnRequest;
import com.jarvis.ai.conversation.dto.VoiceTurnResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceTurnPreparationService {
    private final ConversationService conversations;
    private final VoiceContextSelectionService context;

    public VoiceTurnPreparationService(ConversationService conversations, VoiceContextSelectionService context) {
        this.conversations = conversations;
        this.context = context;
    }

    @Transactional
    public VoiceTurnResponse prepare(UUID conversationId, UUID authenticatedUserId, PrepareVoiceTurnRequest request) {
        ConversationService.MessageWriteResult persisted = conversations.addVoiceUserTranscript(
                conversationId, authenticatedUserId, request.getContent(), request.getExternalEventId());
        VoiceContextSelectionService.Selection selection = context.select(conversationId, authenticatedUserId);
        return new VoiceTurnResponse(persisted.message(), persisted.created(),
                selection.recentMessages(), selection.activeMemories(), selection.budget());
    }
}
