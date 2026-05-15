package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Parameters for the {@code resources/read} JSON-RPC request.
 *
 * @param uri Required. The URI of the resource to read.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReadResourceParams(String uri) {
    public ReadResourceParams {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("ReadResourceParams.uri must not be blank");
        }
    }
}
