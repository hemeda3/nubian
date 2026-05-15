package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.nubian.ai.runtime.mcp.tools.ContentBlock;

import java.io.IOException;

/**
 * Sealed interface representing a single content block within a sampling message.
 *
 * <p>Sampling messages can carry either standard MCP content blocks ({@link PlainContent})
 * or tool-loop blocks ({@link ToolUse}, {@link ToolResult}). Jackson polymorphic
 * deserialization is driven by the {@code "type"} field:
 * <ul>
 *   <li>{@code text}, {@code image}, {@code audio}, {@code resource}, {@code resource_link}
 *       → {@link PlainContent}</li>
 *   <li>{@code tool_use} → {@link ToolUse}</li>
 *   <li>{@code tool_result} → {@link ToolResult}</li>
 * </ul>
 *
 * <p>A custom deserializer ({@link SamplingContentDeserializer}) peeks at the
 * {@code type} field and dispatches accordingly without relying on Jackson's built-in
 * {@code @JsonTypeInfo} (which would conflict with {@link ContentBlock}'s own type info).
 */
@JsonDeserialize(using = SamplingContent.SamplingContentDeserializer.class)
public sealed interface SamplingContent
        permits SamplingContent.PlainContent,
                SamplingContent.ToolUse,
                SamplingContent.ToolResult {

    // ------------------------------------------------------------------
    // PlainContent — wraps any standard ContentBlock variant
    // ------------------------------------------------------------------

    /**
     * Wraps a standard MCP content block (text, image, audio, resource, resource_link).
     *
     * @param block The underlying content block.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PlainContent(ContentBlock block) implements SamplingContent {
        public PlainContent {
            if (block == null) {
                throw new IllegalArgumentException("PlainContent.block must not be null");
            }
        }
    }

    // ------------------------------------------------------------------
    // ToolUse — assistant wants to call a tool
    // ------------------------------------------------------------------

    /**
     * Wraps a {@link ToolUseContent} block emitted by the assistant.
     *
     * @param toolUse The tool-use invocation payload.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolUse(ToolUseContent toolUse) implements SamplingContent {
        public ToolUse {
            if (toolUse == null) {
                throw new IllegalArgumentException("ToolUse.toolUse must not be null");
            }
        }
    }

    // ------------------------------------------------------------------
    // ToolResult — user (client) returns tool execution results
    // ------------------------------------------------------------------

    /**
     * Wraps a {@link ToolResultContent} block returned by the user in reply to a
     * prior {@link ToolUse}.
     *
     * @param toolResult The tool-result payload.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ToolResult(ToolResultContent toolResult) implements SamplingContent {
        public ToolResult {
            if (toolResult == null) {
                throw new IllegalArgumentException("ToolResult.toolResult must not be null");
            }
        }
    }

    // ------------------------------------------------------------------
    // Custom deserializer — peeks at "type" and dispatches
    // ------------------------------------------------------------------

    /**
     * Jackson deserializer that inspects the {@code "type"} field and routes to the
     * correct {@link SamplingContent} variant.
     */
    final class SamplingContentDeserializer extends StdDeserializer<SamplingContent> {

        public SamplingContentDeserializer() {
            super(SamplingContent.class);
        }

        @Override
        public SamplingContent deserialize(JsonParser p, DeserializationContext ctx)
                throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            String type = node.has("type") ? node.get("type").asText("") : "";

            if ("tool_use".equals(type)) {
                ToolUseContent tuc = p.getCodec().treeToValue(node, ToolUseContent.class);
                return new ToolUse(tuc);
            }
            if ("tool_result".equals(type)) {
                ToolResultContent trc = p.getCodec().treeToValue(node, ToolResultContent.class);
                return new ToolResult(trc);
            }
            // text / image / audio / resource / resource_link — all plain ContentBlock
            ContentBlock block = p.getCodec().treeToValue(node, ContentBlock.class);
            return new PlainContent(block);
        }
    }
}
