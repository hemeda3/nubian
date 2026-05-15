package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Parameters for the {@code resources/unsubscribe} JSON-RPC request.
 *
 * @param uri Required. The URI of the resource to stop watching.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnsubscribeParams(String uri) {
    public UnsubscribeParams {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("UnsubscribeParams.uri must not be blank");
        }
    }
}
