package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nubian.ai.runtime.mcp.lifecycle.ClientInfo.Icon;

import java.util.List;

/**
 * Describes a reusable prompt template exposed by an MCP server.
 *
 * <p>Per the MCP spec (2025-11-25 / prompts): prompts are user-controlled templates
 * (think slash commands). Discovered via {@code prompts/list}; resolved via
 * {@code prompts/get}.
 *
 * @param name        Required. Unique identifier within the server (e.g. {@code "code_review"}).
 * @param title       Optional. Short human-readable label for UI display.
 * @param description Optional. Free-text description of what the prompt does.
 * @param arguments   Optional. Ordered list of arguments the prompt accepts.
 * @param icons       Optional. Icon assets (same shape as {@link Icon}).
 *                    Type supplied by slice 2 ({@code com.nubian.ai.runtime.mcp.lifecycle}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Prompt(
        String name,
        String title,
        String description,
        List<PromptArgument> arguments,
        List<Icon> icons) {

    /** Canonical constructor — validates {@code name}. */
    public Prompt {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Prompt.name must not be blank");
        }
    }

    /** Convenience: minimal prompt with no optional fields. */
    public Prompt(String name) {
        this(name, null, null, null, null);
    }

    /** Convenience: prompt with description and arguments, no icons. */
    public Prompt(String name, String description, List<PromptArgument> arguments) {
        this(name, null, description, arguments, null);
    }
}
