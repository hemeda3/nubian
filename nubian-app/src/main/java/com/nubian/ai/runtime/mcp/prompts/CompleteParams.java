package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Parameters for the {@code completion/complete} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25 / completion): the request identifies the completion
 * target via a {@link CompletionRef} and the argument being typed via a
 * {@link CompletionArgument}. An optional {@code context} map carries additional
 * already-bound argument values to allow context-sensitive completions.
 *
 * @param ref      Required. Discriminated reference to a prompt or resource template.
 * @param argument Required. The argument name and current partial value.
 * @param context  Optional. Map of already-resolved argument name → value pairs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompleteParams(
        CompletionRef ref,
        CompletionArgument argument,
        Map<String, String> context) {

    /** Canonical constructor — validates required fields. */
    public CompleteParams {
        if (ref == null) {
            throw new IllegalArgumentException("CompleteParams.ref must not be null");
        }
        if (argument == null) {
            throw new IllegalArgumentException("CompleteParams.argument must not be null");
        }
    }

    /** Convenience: no context. */
    public CompleteParams(CompletionRef ref, CompletionArgument argument) {
        this(ref, argument, null);
    }
}
