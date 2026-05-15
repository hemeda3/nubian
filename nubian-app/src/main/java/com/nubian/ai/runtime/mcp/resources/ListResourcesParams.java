package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Parameters for the {@code resources/list} JSON-RPC request.
 *
 * @param cursor Optional. Opaque pagination cursor from a previous {@code nextCursor} value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListResourcesParams(String cursor) {
}
