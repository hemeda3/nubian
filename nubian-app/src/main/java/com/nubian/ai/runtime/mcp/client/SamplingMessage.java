package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;

/**
 * A single message in a sampling conversation.
 *
 * <p>Per the MCP spec (2025-11-25): messages alternate between {@code user} and
 * {@code assistant} roles. The {@code content} field may be either a single content
 * block (object) or a list of content blocks (array), depending on the message — for
 * example, assistant messages with multiple {@code tool_use} blocks or user messages
 * with multiple {@code tool_result} blocks use the array form. {@link JsonNode} is used
 * to preserve this flexibility at the wire level.
 *
 * @param role    The participant role (required).
 * @param content Single content block (JsonNode object) or list of blocks (JsonNode array).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SamplingMessage(
        @JsonProperty("role") Role role,
        @JsonProperty("content") JsonNode content) {

    public SamplingMessage {
        if (role == null) {
            throw new IllegalArgumentException("SamplingMessage.role must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("SamplingMessage.content must not be null");
        }
    }

    // ------------------------------------------------------------------
    // Static factories
    // ------------------------------------------------------------------

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Creates a simple text message with a single text content block.
     *
     * @param role The participant role.
     * @param text The text content.
     * @return A {@link SamplingMessage} with a single {@code text} content block.
     */
    public static SamplingMessage ofText(Role role, String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        com.fasterxml.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "text");
        node.put("text", text);
        return new SamplingMessage(role, node);
    }

    /**
     * Creates an assistant message carrying one or more {@code tool_use} blocks.
     *
     * @param toolUses List of tool invocations (must not be null or empty).
     * @return A {@link SamplingMessage} with role {@link Role#ASSISTANT} and an array
     *         of {@code tool_use} content blocks.
     */
    public static SamplingMessage ofToolUses(List<ToolUseContent> toolUses) {
        if (toolUses == null || toolUses.isEmpty()) {
            throw new IllegalArgumentException("toolUses must not be null or empty");
        }
        ArrayNode array = MAPPER.createArrayNode();
        for (ToolUseContent tuc : toolUses) {
            array.add(MAPPER.valueToTree(tuc));
        }
        return new SamplingMessage(Role.ASSISTANT, array);
    }

    /**
     * Creates a user message carrying one or more {@code tool_result} blocks.
     *
     * <p>Per the MCP spec (2025-11-25): a user message that contains tool results MUST
     * contain ONLY tool-result blocks — no mixing with text or other content types.
     *
     * @param toolResults List of tool results (must not be null or empty).
     * @return A {@link SamplingMessage} with role {@link Role#USER} and an array of
     *         {@code tool_result} content blocks.
     */
    public static SamplingMessage ofToolResults(List<ToolResultContent> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            throw new IllegalArgumentException("toolResults must not be null or empty");
        }
        ArrayNode array = MAPPER.createArrayNode();
        for (ToolResultContent trc : toolResults) {
            array.add(MAPPER.valueToTree(trc));
        }
        return new SamplingMessage(Role.USER, array);
    }
}
