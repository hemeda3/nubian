package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Parameters for the {@code resources/subscribe} JSON-RPC request.
 *
 * <p>After a successful subscribe, the server will send
 * {@code notifications/resources/updated} whenever the resource at {@code uri} changes.
 *
 * @param uri Required. The URI of the resource to watch.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscribeParams(String uri) {
    public SubscribeParams {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("SubscribeParams.uri must not be blank");
        }
    }
}
