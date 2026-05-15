package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The action taken by the user in response to an elicitation request.
 *
 * <p>Per the MCP spec (2025-11-25):
 * <ul>
 *   <li>{@link #ACCEPT} — user explicitly approved and submitted data</li>
 *   <li>{@link #DECLINE} — user explicitly rejected the request</li>
 *   <li>{@link #CANCEL} — user dismissed without a decision (closed dialog, pressed Esc,
 *       browser failed to load)</li>
 * </ul>
 *
 * <p>Servers should handle each action differently: {@code accept} means proceed,
 * {@code decline} means "no thanks" (do not re-ask), {@code cancel} means "ask me later".
 */
public enum ElicitationAction {

    ACCEPT,
    DECLINE,
    CANCEL;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase();
    }
}
