package com.jarvis.ai.voice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jarvis.ai.voice.config.VoiceProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoiceSessionRateLimiterTest {
    @Test
    void limitsCallsPerUserAndKeepsUsersIndependent() {
        var limiter = new VoiceSessionRateLimiter(new VoiceProperties(2));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(limiter.allow(first)).isTrue();
        assertThat(limiter.allow(first)).isTrue();
        assertThat(limiter.allow(first)).isFalse();
        assertThat(limiter.allow(second)).isTrue();
    }
}
