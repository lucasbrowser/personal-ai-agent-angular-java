package com.jarvis.ai.voice.service;

import com.jarvis.ai.voice.config.VoiceProperties;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class VoiceSessionRateLimiter {
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_USERS = 10_000;
    private final HashMap<UUID, Window> windows = new HashMap<>();
    private final int limit;

    public VoiceSessionRateLimiter(VoiceProperties properties) { this.limit = properties.sessionsPerMinute(); }

    public synchronized boolean allow(UUID userId) {
        long now = System.currentTimeMillis();
        long windowStart = now - now % WINDOW_MILLIS;
        windows.entrySet().removeIf(entry -> entry.getValue().startedAt < windowStart);
        Window current = windows.get(userId);
        if (current == null) {
            if (windows.size() >= MAX_TRACKED_USERS) return false;
            windows.put(userId, new Window(windowStart, 1));
            return true;
        }
        if (current.requests >= limit) return false;
        windows.put(userId, new Window(current.startedAt, current.requests + 1));
        return true;
    }

    private record Window(long startedAt, int requests) { }
}
