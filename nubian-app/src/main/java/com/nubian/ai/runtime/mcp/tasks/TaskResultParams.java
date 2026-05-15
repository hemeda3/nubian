package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parameters for the {@code tasks/result} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): the client sends a
 * {@code tasks/result} request with this params object to retrieve the final result of a
 * completed task. The server SHOULD block until the task reaches a terminal state before
 * responding.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskResultParams(
        @JsonProperty("taskId") String taskId) {
}
