package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Result returned by a {@code tasks/list} call.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): the result carries a page of tasks
 * and an optional cursor for retrieving the next page. When {@code nextCursor} is
 * {@code null} there are no further pages.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskListResult(
        @JsonProperty("tasks") List<Task> tasks,
        @JsonProperty("nextCursor") String nextCursor) {
}
