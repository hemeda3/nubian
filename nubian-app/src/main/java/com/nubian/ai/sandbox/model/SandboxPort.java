package com.nubian.ai.sandbox.model;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Port exposure state for a sandbox session.
 */
public record SandboxPort(
        String providerId,
        String sessionId,
        int port,
        String protocol,
        URI url,
        boolean publicAccess,
        Map<String, String> metadata
) {
    public SandboxPort {
        providerId = Objects.requireNonNull(providerId, "providerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        protocol = Objects.requireNonNullElse(protocol, "http");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
