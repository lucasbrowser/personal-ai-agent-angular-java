package com.jarvis.ai.conversation.controller;

import com.jarvis.ai.conversation.dto.ConversationMessageResponse;
import com.jarvis.ai.conversation.dto.ConversationResponse;
import com.jarvis.ai.conversation.dto.CreateConversationRequest;
import com.jarvis.ai.conversation.dto.CreateMessageRequest;
import com.jarvis.ai.conversation.dto.PageResponse;
import com.jarvis.ai.conversation.dto.PrepareVoiceTurnRequest;
import com.jarvis.ai.conversation.dto.VoiceTurnResponse;
import com.jarvis.ai.conversation.service.ConversationService;
import com.jarvis.ai.conversation.service.VoiceTurnPreparationService;
import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationService conversations;
    private final VoiceTurnPreparationService voiceTurns;

    public ConversationController(ConversationService conversations, VoiceTurnPreparationService voiceTurns) {
        this.conversations = conversations;
        this.voiceTurns = voiceTurns;
    }

    @PostMapping
    public ResponseEntity<ConversationResponse> create(
            @AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody(required = false) CreateConversationRequest request) {
        ConversationResponse created = conversations.create(userId(principal), request);
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<ConversationResponse> list(
            @AuthenticationPrincipal Jwt principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return conversations.list(userId(principal), page, size);
    }

    @GetMapping("/{id}")
    public ConversationResponse get(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID id) {
        return conversations.get(id, userId(principal));
    }

    @GetMapping("/{id}/messages")
    public PageResponse<ConversationMessageResponse> messages(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return conversations.messages(id, userId(principal), page, size);
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<ConversationMessageResponse> addMessage(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID id,
            @Valid @RequestBody CreateMessageRequest request) {
        ConversationService.MessageWriteResult result = conversations.addMessage(id, userId(principal), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.message());
    }

    @PostMapping("/{id}/voice-turns")
    public VoiceTurnResponse prepareVoiceTurn(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID id,
            @Valid @RequestBody PrepareVoiceTurnRequest request) {
        return voiceTurns.prepare(id, userId(principal), request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable UUID id) {
        conversations.delete(id, userId(principal));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt principal) {
        return UUID.fromString(principal.getSubject());
    }
}
