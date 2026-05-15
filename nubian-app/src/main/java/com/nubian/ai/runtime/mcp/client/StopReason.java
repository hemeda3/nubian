package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The reason that a sampling completion stopped generating tokens.
 *
 * <p>Per the MCP spec (2025-11-25):
 * <ul>
 *   <li>{@link #END_TURN} — natural end of the model's response</li>
 *   <li>{@link #MAX_TOKENS} — hit the {@code maxTokens} limit</li>
 *   <li>{@link #STOP_SEQUENCE} — hit a stop sequence string</li>
 *   <li>{@link #TOOL_USE} — model emitted a tool_use block and is awaiting results</li>
 * </ul>
 */
public enum StopReason {

    END_TURN,
    MAX_TOKENS,
    STOP_SEQUENCE,
    TOOL_USE;

    @JsonValue
    public String jsonValue() {
        return switch (this) {
            case END_TURN -> "endTurn";
            case MAX_TOKENS -> "maxTokens";
            case STOP_SEQUENCE -> "stopSequence";
            case TOOL_USE -> "toolUse";
        };
    }
}
