package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parameters for the {@code tasks/get} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): the client sends a {@code tasks/get}
 * request with this params object to retrieve the current state of an identified task.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskGetParams(
        @JsonProperty("taskId") String taskId) {
}
