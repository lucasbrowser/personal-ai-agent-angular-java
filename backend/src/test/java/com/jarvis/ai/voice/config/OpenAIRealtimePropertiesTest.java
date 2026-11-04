package com.jarvis.ai.voice.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenAIRealtimePropertiesTest {
    @Test
    void rejectsMissingApiKeyAndOutOfRangeClientSecretTtl() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var missingKey = new OpenAIRealtimeProperties(" ", "model", "voice", 600,
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        var invalidTtl = new OpenAIRealtimeProperties("key", "model", "voice", 9,
                Duration.ofSeconds(2), Duration.ofSeconds(5));

        assertThat(validator.validate(missingKey)).isNotEmpty();
        assertThat(validator.validate(invalidTtl)).isNotEmpty();
        var configured = new OpenAIRealtimeProperties("sk-super-secret", "model", "voice", 600,
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        assertThat(configured.toString()).doesNotContain("sk-super-secret");
    }

    @Test
    void rejectsNonPositiveHttpTimeouts() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OpenAIRealtimeProperties(
                "key", "model", "voice", 600, Duration.ZERO, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
