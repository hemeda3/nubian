package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Content block representing a tool-use invocation by the assistant.
 *
 * <p>Per the MCP spec (2025-11-25): when the model wants to call a tool it emits a
 * {@code tool_use} block carrying the tool name, a unique call id, and the input args
 * as an arbitrary JSON object.
 *
 * @param type  Constant {@code "tool_use"} — the type discriminator.
 * @param id    Unique identifier for this tool call (required).
 * @param name  Name of the tool to invoke (required).
 * @param input Arbitrary JSON input for the tool (may be an empty object but not null).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolUseContent(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("input") JsonNode input) {

    public ToolUseContent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ToolUseContent.id must not be null or blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolUseContent.name must not be null or blank");
        }
        if (type == null) {
            type = "tool_use";
        }
    }

    /** Convenience constructor — type defaults to {@code "tool_use"}. */
    public ToolUseContent(String id, String name, JsonNode input) {
        this("tool_use", id, name, input);
    }
}
