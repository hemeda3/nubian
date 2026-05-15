package com.nubian.ai.sandbox.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Captured sandbox display frame.
 */
public record SandboxDisplayFrame(
        String providerId,
        String sessionId,
        int width,
        int height,
        String mediaType,
        byte[] data,
        Instant capturedAt,
        Map<String, String> metadata
) {
    public SandboxDisplayFrame {
        providerId = Objects.requireNonNull(providerId, "providerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        mediaType = Objects.requireNonNullElse(mediaType, "image/png");
        data = data == null ? new byte[0] : data.clone();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
