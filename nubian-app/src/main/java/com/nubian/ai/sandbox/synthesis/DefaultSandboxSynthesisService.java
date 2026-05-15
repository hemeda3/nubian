package com.nubian.ai.sandbox.synthesis;

import com.nubian.ai.sandbox.store.SandboxEvent;
import com.nubian.ai.sandbox.store.SandboxSessionStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class DefaultSandboxSynthesisService implements SandboxSynthesisService {
    private final SandboxSessionStore sessionStore;

    public DefaultSandboxSynthesisService(SandboxSessionStore sessionStore) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore is required");
    }

    @Override
    public SandboxRuntimeSnapshot synthesizeProvider(String providerId, Map<String, String> labels) {
        return new SandboxRuntimeSnapshot(
                providerId,
                sessionStore.list(providerId, labels == null ? Map.of() : labels),
                null,
                eventTypeCounts(providerId),
                Instant.now());
    }

    @Override
    public SandboxSessionTimeline synthesizeSession(String providerId, String sessionId) {
        return new SandboxSessionTimeline(
                providerId,
                sessionId,
                sessionStore.find(providerId, sessionId),
                sessionStore.listEvents(providerId, sessionId),
                Instant.now());
    }

    private Map<String, Long> eventTypeCounts(String providerId) {
        return sessionStore.listEvents(providerId).stream()
                .collect(Collectors.groupingBy(
                        SandboxEvent::eventType,
                        java.util.LinkedHashMap::new,
                        Collectors.counting()));
    }
}
