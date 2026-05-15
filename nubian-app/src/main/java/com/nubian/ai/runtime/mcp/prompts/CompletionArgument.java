package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Identifies the argument being auto-completed and its current (partial) value.
 *
 * <p>Per the MCP spec (2025-11-25 / completion): the {@code argument} object in a
 * {@code completion/complete} request carries the argument {@code name} and the
 * current user-typed {@code value} (which may be a partial string).
 *
 * @param name  Required. Argument name (matches a {@link PromptArgument#name()}).
 * @param value Required. Current partial value entered by the user.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompletionArgument(String name, String value) {

    /** Canonical constructor — validates required fields. */
    public CompletionArgument {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CompletionArgument.name must not be blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("CompletionArgument.value must not be null");
        }
    }
}
