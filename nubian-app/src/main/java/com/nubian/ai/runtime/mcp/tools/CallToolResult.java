package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Result of a {@code tools/call} JSON-RPC request.
 *
 * <p>Per the MCP spec (2025-11-25), there are two error modes:
 * <ul>
 *   <li><b>Tool execution error</b> — result is returned with {@code isError: true}.
 *       The LLM receives the error text in {@code content} and can self-correct.</li>
 *   <li><b>Protocol error</b> — transport raises a JSON-RPC error response, which
 *       maps to {@link com.nubian.ai.runtime.mcp.tools.McpProtocolException}.</li>
 * </ul>
 *
 * <p>Both {@code content} (unstructured) and {@code structuredContent} (validated against
 * {@code outputSchema}) may be present on the same response for backwards compatibility.
 *
 * @param content           Unstructured list of content blocks (text, image, audio, resource).
 * @param structuredContent Optional JSON object validated against the tool's {@code outputSchema}.
 * @param isError           True when the tool itself reported a logical execution error.
 * @param _meta             Optional protocol-level metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CallToolResult(
        @JsonProperty("content") List<ContentBlock> content,
        @JsonProperty("structuredContent") JsonNode structuredContent,
        @JsonProperty("isError") Boolean isError,
        @JsonProperty("_meta") Map<String, Object> _meta) {

    public CallToolResult {
        if (content == null) {
            content = List.of();
        }
        if (isError == null) {
            isError = false;
        }
    }

    /**
     * Returns true if this result represents a tool execution error.
     * Tool errors are designed for LLM self-correction — they carry actionable text
     * in {@code content} and do NOT indicate a broken transport or invalid request.
     */
    public boolean hasError() {
        return Boolean.TRUE.equals(isError);
    }

    // ------------------------------------------------------------------
    // Static factories
    // ------------------------------------------------------------------

    /** Factory for a successful result with an unstructured content list. */
    public static CallToolResult success(List<ContentBlock> content) {
        return new CallToolResult(content, null, false, null);
    }

    /** Factory for a successful result with both content and structured output. */
    public static CallToolResult success(List<ContentBlock> content, JsonNode structuredContent) {
        return new CallToolResult(content, structuredContent, false, null);
    }

    /**
     * Factory for a tool execution error.
     *
     * <p>The error message is wrapped in a {@link ContentBlock.TextContent} so the LLM
     * receives it as actionable context. This is NOT a protocol error.
     */
    public static CallToolResult error(String message) {
        return new CallToolResult(
                List.of(new ContentBlock.TextContent(message)),
                null,
                true,
                null);
    }
}
