package com.nubian.ai.runtime.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 error object as defined in the MCP spec (2025-11-25).
 *
 * <p>Standard error codes follow JSON-RPC 2.0 plus MCP-specific extensions:
 * <ul>
 *   <li>{@link #PARSE_ERROR} (-32700) — invalid JSON received by the server</li>
 *   <li>{@link #INVALID_REQUEST} (-32600) — JSON sent is not a valid Request object</li>
 *   <li>{@link #METHOD_NOT_FOUND} (-32601) — method does not exist / is not available</li>
 *   <li>{@link #INVALID_PARAMS} (-32602) — invalid method parameter(s)</li>
 *   <li>{@link #INTERNAL_ERROR} (-32603) — internal JSON-RPC error</li>
 *   <li>{@link #URL_ELICITATION_REQUIRED} (-32042) — MCP: server needs a URL from client</li>
 *   <li>{@link #RESOURCE_NOT_FOUND} (-32002) — MCP: requested resource does not exist</li>
 *   <li>{@link #USER_REJECTED} (-1) — MCP: user rejected the operation</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorObject(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("data") JsonNode data) {

    // ---- Standard JSON-RPC 2.0 codes ----
    public static final int PARSE_ERROR      = -32700;
    public static final int INVALID_REQUEST  = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS   = -32602;
    public static final int INTERNAL_ERROR   = -32603;

    // ---- MCP-specific codes ----
    /** Server requires a URL from the client (elicitation). */
    public static final int URL_ELICITATION_REQUIRED = -32042;
    /** Requested resource was not found. */
    public static final int RESOURCE_NOT_FOUND       = -32002;
    /** User rejected the operation on the client side. */
    public static final int USER_REJECTED            = -1;

    /** Compact constructor — message must not be null or blank. */
    public ErrorObject {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("ErrorObject.message must not be null or blank");
        }
    }

    /** Convenience factory without data. */
    public static ErrorObject of(int code, String message) {
        return new ErrorObject(code, message, null);
    }
}
