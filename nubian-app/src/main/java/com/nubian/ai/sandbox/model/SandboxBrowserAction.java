package com.nubian.ai.sandbox.model;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Browser automation request for a sandbox session.
 */
public record SandboxBrowserAction(
        Type type,
        Map<String, String> parameters,
        Duration timeout,
        Map<String, String> metadata
) {
    public SandboxBrowserAction {
        type = Objects.requireNonNull(type, "type");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum Type {
        NAVIGATE,
        CLICK,
        TYPE,
        PRESS_KEY,
        SCROLL,
        BACK,
        FORWARD,
        RELOAD,
        WAIT,
        EVALUATE,
        SCREENSHOT
    }
}
