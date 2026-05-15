package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Describes a single argument accepted by a {@link Prompt}.
 *
 * <p>Per the MCP spec (2025-11-25 / prompts): argument objects carry a {@code name},
 * an optional human-readable {@code description}, and an optional {@code required}
 * flag (defaults to {@code false} when absent).
 *
 * @param name        Required. Identifier used when calling {@code prompts/get}.
 * @param description Optional. Human-readable explanation of the argument.
 * @param required    Optional. Whether the argument must be supplied. Defaults to {@code false}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PromptArgument(
        String name,
        String description,
        Boolean required) {

    /** Canonical constructor — validates {@code name}. */
    public PromptArgument {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("PromptArgument.name must not be blank");
        }
    }

    /** Convenience: argument with no description, not required. */
    public PromptArgument(String name) {
        this(name, null, null);
    }

    /** Convenience: argument with description, not required. */
    public PromptArgument(String name, String description) {
        this(name, description, null);
    }

    /** Returns {@code true} when this argument must be supplied. */
    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
}
