package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Parameters for the {@code prompts/get} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25 / prompts): the client supplies the prompt {@code name}
 * and optionally a map of argument values keyed by argument name. Arguments are always
 * string-valued at the wire level.
 *
 * @param name      Required. Exact prompt name as returned by {@code prompts/list}.
 * @param arguments Optional. Map of argument name → string value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GetPromptParams(
        String name,
        Map<String, String> arguments) {

    /** Canonical constructor — validates {@code name}. */
    public GetPromptParams {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("GetPromptParams.name must not be blank");
        }
    }

    /** Convenience: request with no arguments. */
    public GetPromptParams(String name) {
        this(name, null);
    }
}
