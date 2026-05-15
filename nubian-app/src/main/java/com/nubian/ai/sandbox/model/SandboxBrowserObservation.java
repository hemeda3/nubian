package com.nubian.ai.sandbox.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Browser state observed after an action or direct inspection.
 */
public record SandboxBrowserObservation(
        String providerId,
        String sessionId,
        String url,
        String title,
        String text,
        String screenshotMediaType,
        byte[] screenshot,
        Instant observedAt,
        Map<String, String> metadata
) {
    public SandboxBrowserObservation {
        providerId = Objects.requireNonNull(providerId, "providerId");
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        url = Objects.requireNonNullElse(url, "");
        title = Objects.requireNonNullElse(title, "");
        text = Objects.requireNonNullElse(text, "");
        screenshotMediaType = Objects.requireNonNullElse(screenshotMediaType, "image/png");
        screenshot = screenshot == null ? new byte[0] : screenshot.clone();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    @Override
    public byte[] screenshot() {
        return screenshot.clone();
    }
}
