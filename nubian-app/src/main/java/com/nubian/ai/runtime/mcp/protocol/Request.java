package com.nubian.ai.runtime.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 Request message.
 *
 * <p>Per the MCP spec (2025-11-25): "A rpc call is represented by sending a Request
 * object to a Server. The Request object has the following members:
 * <ul>
 *   <li>{@code jsonrpc} — A String specifying the version of the JSON-RPC protocol.
 *       MUST be exactly "2.0".</li>
 *   <li>{@code id} — An identifier established by the Client. MUST contain a String
 *       or Number value. The value MUST NOT be Null.</li>
 *   <li>{@code method} — A String containing the name of the method to be invoked.</li>
 *   <li>{@code params} — A Structured value that holds the parameter values (optional).</li>
 * </ul>
 *
 * <p>The compact constructor enforces that {@code id} and {@code method} are non-null
 * and that {@code method} is non-blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Request(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") RequestId id,
        @JsonProperty("method") String method,
        @JsonProperty("params") JsonNode params) implements JsonRpcMessage {

    public Request {
        if (id == null) {
            throw new IllegalArgumentException("Request.id must not be null (spec: ID MUST NOT be null)");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("Request.method must not be null or blank");
        }
        if (jsonrpc == null) {
            jsonrpc = "2.0";
        }
    }

    /** Convenience constructor — sets jsonrpc to "2.0" automatically. */
    public Request(RequestId id, String method, JsonNode params) {
        this("2.0", id, method, params);
    }

    /** Convenience constructor — no params. */
    public Request(RequestId id, String method) {
        this("2.0", id, method, null);
    }
}
