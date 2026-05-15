package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.nubian.ai.runtime.mcp.tasks.TaskAugmentation;

import java.util.Map;

/**
 * Parameters for the {@code tools/call} JSON-RPC request.
 *
 * <p>Per the MCP spec (2025-11-25): {@code name} identifies the tool; {@code arguments}
 * is a JSON object validated against the tool's {@code inputSchema}.
 *
 * <p>The optional {@code task} field enables task-augmented invocation — the server
 * returns a {@code CreateTaskResult} instead of a normal tool result, and the caller
 * polls the tasks API for completion.
 *
 * @param name      Tool name (required).
 * @param arguments Optional JSON object of tool arguments.
 * @param task      Optional task-augmentation parameters.
 * @param _meta     Optional protocol-level metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CallToolParams(
        @JsonProperty("name") String name,
        @JsonProperty("arguments") JsonNode arguments,
        @JsonProperty("task") TaskAugmentation task,
        @JsonProperty("_meta") Map<String, Object> _meta) {

    public CallToolParams {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CallToolParams.name must not be null or blank");
        }
    }

    /** Convenience constructor — name and arguments only. */
    public CallToolParams(String name, JsonNode arguments) {
        this(name, arguments, null, null);
    }

    /** Convenience constructor — name only (no arguments). */
    public CallToolParams(String name) {
        this(name, null, null, null);
    }
}
