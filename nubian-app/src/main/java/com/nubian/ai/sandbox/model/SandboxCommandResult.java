package com.nubian.ai.sandbox.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a terminal command executed inside a sandbox session.
 */
public record SandboxCommandResult(
        String providerId,
        String sessionId,
        String commandId,
        int exitCode,
        String stdout,
        String stderr,
        Instant startedAt,
        Instant completedAt,
        Optional<SandboxFailure> failure,
        Map<String, String> metadata
) {
    public SandboxCommandResult {
        providerId = Objects.requireNonNull(providerId, "providerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        commandId = Objects.requireNonNullElse(commandId, "");
        stdout = Objects.requireNonNullElse(stdout, "");
        stderr = Objects.requireNonNullElse(stderr, "");
        failure = failure == null ? Optional.empty() : failure;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean successful() {
        return exitCode == 0 && failure.isEmpty();
    }
}
