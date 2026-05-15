package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parameters for the {@code tasks/cancel} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): the client sends a
 * {@code tasks/cancel} request with this params object to request cancellation of the
 * identified task.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskCancelParams(
        @JsonProperty("taskId") String taskId) {
}
