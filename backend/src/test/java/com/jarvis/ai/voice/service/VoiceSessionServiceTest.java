package com.jarvis.ai.voice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoiceSessionServiceTest {
    @Test
    void safetyIdentifierIsStableLowercaseSha256HexAndBoundToInternalUserId() {
        UUID firstUser = UUID.fromString("2bbf0e76-1200-457e-87b6-10b8cf2d1ab4");
        UUID secondUser = UUID.fromString("d2492a11-fda6-4018-9cee-6aa0d8e15f73");

        String first = VoiceSessionService.safetyIdentifier(firstUser);
        String repeated = VoiceSessionService.safetyIdentifier(firstUser);
        String second = VoiceSessionService.safetyIdentifier(secondUser);

        assertThat(first).isEqualTo(repeated);
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(second).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(first);
        assertThat(first).doesNotContain(firstUser.toString());
    }
}
