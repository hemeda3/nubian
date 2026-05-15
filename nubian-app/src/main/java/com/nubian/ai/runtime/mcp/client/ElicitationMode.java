package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The interaction mode for an elicitation request.
 *
 * <p>Per the MCP spec (2025-11-25):
 * <ul>
 *   <li>{@link #FORM} — structured data collection via JSON Schema, rendered inline by
 *       the client UI</li>
 *   <li>{@link #URL} — sensitive interaction (auth, payment, credentials) handled in an
 *       external browser window; the client never sees the submitted data</li>
 * </ul>
 */
public enum ElicitationMode {

    FORM,
    URL;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase();
    }
}
