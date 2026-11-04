package com.jarvis.ai.voice.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jarvis.openai.realtime")
public record OpenAIRealtimeProperties(
        @NotBlank String apiKey,
        @NotBlank String model,
        @NotBlank String voice,
        @Min(10) @Max(7200) int clientSecretTtlSeconds,
        @NotNull Duration connectTimeout,
        @NotNull Duration responseTimeout) {
    public OpenAIRealtimeProperties {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) {
            throw new IllegalArgumentException("Realtime HTTP timeouts must be positive");
        }
    }

    @Override
    public String toString() {
        return "OpenAIRealtimeProperties[apiKey=[REDACTED], model=" + model + ", voice=" + voice
                + ", clientSecretTtlSeconds=" + clientSecretTtlSeconds + ", connectTimeout=" + connectTimeout
                + ", responseTimeout=" + responseTimeout + "]";
    }
}
