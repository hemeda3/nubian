package com.nubian.ai.sandbox.model;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral failure detail suitable for failed futures and partial results.
 */
public record SandboxFailure(
        String providerId,
        String sessionId,
        SandboxFailureCode code,
        String message,
        String operation,
        boolean retryable,
        Map<String, String> metadata
) {
    public SandboxFailure {
        providerId = Objects.requireNonNullElse(providerId, "");
        sessionId = Objects.requireNonNullElse(sessionId, "");
        code = Objects.requireNonNullElse(code, SandboxFailureCode.UNKNOWN);
        message = Objects.requireNonNullElse(message, "");
        operation = Objects.requireNonNullElse(operation, "");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static SandboxFailure of(
            String providerId,
            String sessionId,
            SandboxFailureCode code,
            String message,
            String operation
    ) {
        SandboxFailureCode resolvedCode = Objects.requireNonNullElse(code, SandboxFailureCode.UNKNOWN);
        return new SandboxFailure(
                providerId,
                sessionId,
                resolvedCode,
                message,
                operation,
                resolvedCode.retryable(),
                Map.of()
        );
    }
}
