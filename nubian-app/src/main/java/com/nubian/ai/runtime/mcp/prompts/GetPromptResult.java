package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result returned by the {@code prompts/get} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25 / prompts): the response carries an optional
 * description (may differ from the listing description) and the resolved
 * {@link PromptMessage} sequence ready for injection into the conversation.
 *
 * @param description Optional. Override description for this particular invocation.
 * @param messages    Required. Ordered list of messages that make up the prompt.
 * @param _meta       Optional. Protocol-level metadata (e.g. progress tokens).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GetPromptResult(
        String description,
        List<PromptMessage> messages,
        @JsonProperty("_meta") Map<String, Object> _meta) {

    /** Canonical constructor — ensures {@code messages} list is non-null. */
    public GetPromptResult {
        if (messages == null) {
            throw new IllegalArgumentException("GetPromptResult.messages must not be null");
        }
    }

    /** Convenience: result with messages only (no description, no meta). */
    public GetPromptResult(List<PromptMessage> messages) {
        this(null, messages, null);
    }
}
