package com.nubian.ai.sandbox.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A provider-owned sandbox execution environment.
 */
public record SandboxSession(
        String providerId,
        String sessionId,
        SandboxSessionStatus status,
        Instant createdAt,
        Instant updatedAt,
        Map<String, String> labels,
        Map<String, String> metadata,
        Optional<SandboxFailure> failure
) {
    public SandboxSession {
        providerId = Objects.requireNonNull(providerId, "providerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        status = Objects.requireNonNullElse(status, SandboxSessionStatus.UNKNOWN);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        failure = failure == null ? Optional.empty() : failure;
    }
}
