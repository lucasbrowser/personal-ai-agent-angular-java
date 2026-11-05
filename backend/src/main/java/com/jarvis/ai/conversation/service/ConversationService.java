package com.jarvis.ai.conversation.service;

import com.jarvis.ai.conversation.domain.Conversation;
import com.jarvis.ai.conversation.domain.ConversationMessage;
import com.jarvis.ai.conversation.domain.ConversationStatus;
import com.jarvis.ai.conversation.domain.MessageRole;
import com.jarvis.ai.conversation.domain.MessageSource;
import com.jarvis.ai.conversation.dto.ConversationMessageResponse;
import com.jarvis.ai.conversation.dto.ConversationResponse;
import com.jarvis.ai.conversation.dto.CreateConversationRequest;
import com.jarvis.ai.conversation.dto.CreateMessageRequest;
import com.jarvis.ai.conversation.dto.PageResponse;
import com.jarvis.ai.conversation.repository.ConversationMessageRepository;
import com.jarvis.ai.conversation.repository.ConversationRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    public ConversationService(ConversationRepository conversations, ConversationMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    public ConversationResponse create(UUID authenticatedUserId, CreateConversationRequest request) {
        Instant now = Instant.now();
        String title = request == null || request.title() == null ? null : request.title().trim();
        if (title != null && title.isEmpty()) title = null;
        Conversation conversation = new Conversation(UUID.randomUUID(), authenticatedUserId, title,
                ConversationStatus.ACTIVE, now, now, null);
        conversations.save(conversation);
        return response(conversation);
    }

    public PageResponse<ConversationResponse> list(UUID authenticatedUserId, int page, int size) {
        long total = conversations.countByUserId(authenticatedUserId);
        var items = conversations.findPageByUserId(authenticatedUserId, size, offset(page, size))
                .stream().map(ConversationService::response).toList();
        return PageResponse.of(items, page, size, total);
    }

    public ConversationResponse get(UUID conversationId, UUID authenticatedUserId) {
        return conversations.findByIdAndUserId(conversationId, authenticatedUserId)
                .map(ConversationService::response)
                .orElseThrow(ConversationNotFoundException::new);
    }

    public PageResponse<ConversationMessageResponse> messages(
            UUID conversationId, UUID authenticatedUserId, int page, int size) {
        requireOwnedConversation(conversationId, authenticatedUserId);
        long total = messages.countByConversationAndUserId(conversationId, authenticatedUserId);
        var items = messages.findPageByConversationAndUserId(
                        conversationId, authenticatedUserId, size, offset(page, size))
                .stream().map(ConversationService::response).toList();
        return PageResponse.of(items, page, size, total);
    }

    /**
     * The browser submits finalized Realtime transcripts. Role and source identify the claimed transcript
     * type, but they do not cryptographically prove that the content came from OpenAI.
     */
    @Transactional
    public MessageWriteResult addMessage(UUID conversationId, UUID authenticatedUserId, CreateMessageRequest request) {
        requireOwnedConversation(conversationId, authenticatedUserId);
        if (!sourceMatchesRole(request.role(), request.source())) throw new InvalidMessageException();

        return persistMessage(conversationId, authenticatedUserId, request.role(), request.content(),
                request.source(), request.externalEventId());
    }

    /** Persists a client-submitted voice transcript with role and source fixed by the backend. */
    @Transactional
    public MessageWriteResult addVoiceUserTranscript(
            UUID conversationId, UUID authenticatedUserId, String content, String externalEventId) {
        requireOwnedConversation(conversationId, authenticatedUserId);
        return persistMessage(conversationId, authenticatedUserId, MessageRole.USER, content,
                MessageSource.REALTIME_USER_TRANSCRIPT, externalEventId);
    }

    private MessageWriteResult persistMessage(UUID conversationId, UUID authenticatedUserId,
                                               MessageRole role, String content, MessageSource source,
                                               String suppliedExternalEventId) {

        String externalEventId = suppliedExternalEventId == null || suppliedExternalEventId.isBlank()
                ? null : suppliedExternalEventId.trim();
        ConversationMessage message = new ConversationMessage(UUID.randomUUID(), conversationId, authenticatedUserId,
                role, content, source, externalEventId, Instant.now());
        if (messages.save(message)) return new MessageWriteResult(response(message), true);

        ConversationMessage existing = messages.findByConversationSourceAndExternalEventId(
                        conversationId, authenticatedUserId, source, externalEventId)
                .orElseThrow(() -> new IllegalStateException("Deduplicated conversation message was not found"));
        return new MessageWriteResult(response(existing), false);
    }

    public void delete(UUID conversationId, UUID authenticatedUserId) {
        if (!conversations.deleteByIdAndUserId(conversationId, authenticatedUserId)) {
            throw new ConversationNotFoundException();
        }
    }

    private void requireOwnedConversation(UUID conversationId, UUID authenticatedUserId) {
        if (conversations.findByIdAndUserId(conversationId, authenticatedUserId).isEmpty()) {
            throw new ConversationNotFoundException();
        }
    }

    private static boolean sourceMatchesRole(MessageRole role, MessageSource source) {
        return (role == MessageRole.USER && source == MessageSource.REALTIME_USER_TRANSCRIPT)
                || (role == MessageRole.ASSISTANT && source == MessageSource.REALTIME_ASSISTANT_TRANSCRIPT);
    }

    private static long offset(int page, int size) {
        return (long) page * size;
    }

    private static ConversationResponse response(Conversation conversation) {
        return new ConversationResponse(conversation.id(), conversation.title(), conversation.status(),
                conversation.createdAt(), conversation.updatedAt(), conversation.lastMessageAt());
    }

    private static ConversationMessageResponse response(ConversationMessage message) {
        return new ConversationMessageResponse(message.id(), message.conversationId(), message.role(),
                message.content(), message.source(), message.externalEventId(), message.createdAt());
    }

    public record MessageWriteResult(ConversationMessageResponse message, boolean created) {
    }
}
