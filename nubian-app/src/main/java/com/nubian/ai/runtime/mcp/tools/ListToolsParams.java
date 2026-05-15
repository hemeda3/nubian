package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parameters for the {@code tools/list} JSON-RPC request.
 *
 * <p>Per the MCP spec (2025-11-25): tool listing is paginated via a cursor.
 * Omit {@code cursor} on the first request; use the value from the previous
 * response's {@code nextCursor} for subsequent pages.
 *
 * @param cursor Optional opaque pagination cursor from a previous {@code tools/list} response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListToolsParams(
        @JsonProperty("cursor") String cursor) {

    /** Params for the first page (no cursor). */
    public static final ListToolsParams FIRST_PAGE = new ListToolsParams(null);
}
