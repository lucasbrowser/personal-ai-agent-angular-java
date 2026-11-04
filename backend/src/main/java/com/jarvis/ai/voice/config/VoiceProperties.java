package com.jarvis.ai.voice.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jarvis.voice")
public record VoiceProperties(@Min(1) @Max(60) int sessionsPerMinute) {
}
