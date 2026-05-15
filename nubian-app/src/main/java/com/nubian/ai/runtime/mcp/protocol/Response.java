package com.nubian.ai.runtime.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 Response — sealed interface with two variants.
 *
 * <p>Per the MCP spec (2025-11-25): "When a rpc call is made, the Server MUST reply
 * with a Response, except for in the case of Notifications. The Response is expressed
 * as a single JSON Object, with either a {@code result} or an {@code error} member,
 * but not both."
 *
 * <p>Permitted variants:
 * <ul>
 *   <li>{@link SuccessResponse} — carries {@code result} (non-null per spec)</li>
 *   <li>{@link ErrorResponse} — carries {@code error}; {@code id} may be null when
 *       the request ID could not be determined (e.g. parse error)</li>
 * </ul>
 */
public sealed interface Response extends JsonRpcMessage
        permits Response.SuccessResponse, Response.ErrorResponse {

    /**
     * Successful JSON-RPC response.
     *
     * <p>{@code id} MUST match the id of the originating Request (non-null).
     * {@code result} MUST be present.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SuccessResponse(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") RequestId id,
            @JsonProperty("result") JsonNode result) implements Response {

        public SuccessResponse {
            if (id == null) {
                throw new IllegalArgumentException("SuccessResponse.id must not be null");
            }
            if (result == null) {
                throw new IllegalArgumentException("SuccessResponse.result must not be null");
            }
            if (jsonrpc == null) {
                jsonrpc = "2.0";
            }
        }

        /** Convenience constructor — sets jsonrpc to "2.0" automatically. */
        public SuccessResponse(RequestId id, JsonNode result) {
            this("2.0", id, result);
        }
    }

    /**
     * Error JSON-RPC response.
     *
     * <p>{@code id} SHOULD match the originating request id, but MAY be null when
     * the id could not be determined (e.g. a parse error before the id was read).
     * {@code error} MUST be present.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorResponse(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") RequestId id,
            @JsonProperty("error") ErrorObject error) implements Response {

        public ErrorResponse {
            if (error == null) {
                throw new IllegalArgumentException("ErrorResponse.error must not be null");
            }
            if (jsonrpc == null) {
                jsonrpc = "2.0";
            }
        }

        /** Convenience constructor — sets jsonrpc to "2.0" automatically. id may be null. */
        public ErrorResponse(RequestId id, ErrorObject error) {
            this("2.0", id, error);
        }

        /** Convenience constructor for responses where the request id is unknown. */
        public ErrorResponse(ErrorObject error) {
            this("2.0", null, error);
        }
    }
}
