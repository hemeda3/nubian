package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parameters for the {@code tasks/list} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): the client MAY supply a
 * {@code cursor} from a previous {@link TaskListResult#nextCursor()} to retrieve the
 * next page of tasks. When {@code cursor} is {@code null} the first page is returned.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskListParams(
        @JsonProperty("cursor") String cursor) {
}
