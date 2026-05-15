package com.nubian.ai.sandbox.store;

import java.time.Instant;
import java.util.Map;

public record SandboxEvent(
        String eventId,
        String providerId,
        String sessionId,
        String eventType,
        Instant occurredAt,
        Map<String, String> attributes) {
    public SandboxEvent {
        eventId = eventId == null || eventId.isBlank()
                ? java.util.UUID.randomUUID().toString()
                : eventId;
        providerId = providerId == null ? "" : providerId;
        sessionId = sessionId == null ? "" : sessionId;
        eventType = eventType == null ? "" : eventType;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static SandboxEvent of(String providerId, String sessionId, String eventType, Map<String, String> attributes) {
        return new SandboxEvent(null, providerId, sessionId, eventType, Instant.now(), attributes);
    }
}
