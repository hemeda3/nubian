package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Role of a participant in a sampling message conversation.
 *
 * <p>Per the MCP spec (2025-11-25): messages alternate between {@code user} and
 * {@code assistant} roles in the sampling conversation history.
 */
public enum Role {

    USER,
    ASSISTANT;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase();
    }
}
