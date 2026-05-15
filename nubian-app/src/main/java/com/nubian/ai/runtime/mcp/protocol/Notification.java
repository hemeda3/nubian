package com.nubian.ai.runtime.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 Notification message (no {@code id} field).
 *
 * <p>Per the MCP spec (2025-11-25): "A Notification is a Request object without an
 * 'id' member. A Request object that is a Notification signifies the Client's lack
 * of interest in the corresponding Response object, and as such no Response object
 * needs to be returned to the client."
 *
 * <p>This record intentionally has no {@code id} field. The compact constructor
 * enforces that {@code method} is non-null and non-blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Notification(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("method") String method,
        @JsonProperty("params") JsonNode params) implements JsonRpcMessage {

    public Notification {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("Notification.method must not be null or blank");
        }
        // Always emit the protocol version marker.
        if (jsonrpc == null) {
            jsonrpc = "2.0";
        }
    }

    /** Convenience constructor — sets jsonrpc to "2.0" automatically. */
    public Notification(String method, JsonNode params) {
        this("2.0", method, params);
    }

    /** Convenience constructor — no params. */
    public Notification(String method) {
        this("2.0", method, null);
    }
}
