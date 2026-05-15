package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Controls whether the client should include MCP context (resources, prompts) in the
 * sampling request alongside the explicit {@code messages}.
 *
 * <p><strong>Soft-deprecated</strong> per the MCP spec (2025-11-25). New code should
 * prefer explicit message construction over relying on automatic context inclusion.
 * This enum is retained for wire-compatibility with servers that still send it.
 *
 * <p>Wire values:
 * <ul>
 *   <li>{@code "none"} — do not include any context</li>
 *   <li>{@code "thisServer"} — include context from the requesting server only</li>
 *   <li>{@code "allServers"} — include context from all connected servers</li>
 * </ul>
 */
public enum IncludeContext {

    NONE,
    THIS_SERVER,
    ALL_SERVERS;

    @JsonValue
    public String jsonValue() {
        return switch (this) {
            case NONE -> "none";
            case THIS_SERVER -> "thisServer";
            case ALL_SERVERS -> "allServers";
        };
    }
}
