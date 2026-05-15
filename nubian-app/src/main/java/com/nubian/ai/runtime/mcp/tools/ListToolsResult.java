package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result of a {@code tools/list} JSON-RPC request.
 *
 * <p>Per the MCP spec (2025-11-25): if {@code nextCursor} is non-null, more pages
 * are available — pass it as the {@code cursor} in the next request.
 *
 * @param tools      The list of tool definitions on this page (never null, may be empty).
 * @param nextCursor Optional opaque cursor for the next page; null when no more pages.
 * @param _meta      Optional protocol-level metadata (extension point).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListToolsResult(
        @JsonProperty("tools") List<ToolDefinition> tools,
        @JsonProperty("nextCursor") String nextCursor,
        @JsonProperty("_meta") Map<String, Object> _meta) {

    public ListToolsResult {
        if (tools == null) {
            tools = List.of();
        }
    }
}
