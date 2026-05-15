package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nubian.ai.runtime.mcp.tools.ContentBlock;

import java.util.List;

/**
 * Content block carrying the result of a tool call, sent by the user (client) in reply
 * to a prior {@link ToolUseContent} block.
 *
 * <p>Per the MCP spec (2025-11-25): tool-result blocks MUST reference the originating
 * tool call by {@code toolUseId} and MUST appear in a user message that contains ONLY
 * tool-result blocks.
 *
 * @param type       Constant {@code "tool_result"} — the type discriminator.
 * @param toolUseId  Id of the corresponding {@link ToolUseContent} block (required).
 * @param content    The result payload (list of content blocks; may be empty).
 * @param isError    If {@code true}, the tool call resulted in an error (nullable).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResultContent(
        @JsonProperty("type") String type,
        @JsonProperty("toolUseId") String toolUseId,
        @JsonProperty("content") List<ContentBlock> content,
        @JsonProperty("isError") Boolean isError) {

    public ToolResultContent {
        if (toolUseId == null || toolUseId.isBlank()) {
            throw new IllegalArgumentException("ToolResultContent.toolUseId must not be null or blank");
        }
        if (type == null) {
            type = "tool_result";
        }
    }

    /** Convenience constructor — no error flag. */
    public ToolResultContent(String toolUseId, List<ContentBlock> content) {
        this("tool_result", toolUseId, content, null);
    }

    /** Convenience constructor — no error flag, no content. */
    public ToolResultContent(String toolUseId) {
        this("tool_result", toolUseId, null, null);
    }
}
