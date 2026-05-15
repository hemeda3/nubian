package com.nubian.ai.sandbox.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Durable artifact produced by or imported into a sandbox session.
 */
public record SandboxArtifact(
        String providerId,
        String sessionId,
        String artifactId,
        String name,
        String path,
        String mediaType,
        long sizeBytes,
        URI uri,
        Instant createdAt,
        Map<String, String> metadata
) {
    public SandboxArtifact {
        providerId = Objects.requireNonNull(providerId, "providerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        artifactId = Objects.requireNonNull(artifactId, "artifactId");
        name = Objects.requireNonNullElse(name, "");
        path = Objects.requireNonNullElse(path, "");
        mediaType = Objects.requireNonNullElse(mediaType, "application/octet-stream");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
