package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parameters carried by a {@code notifications/tasks/status} server-initiated
 * notification.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): when a task's status changes the
 * server MAY push a notification to subscribed clients. Clients that have registered a
 * handler via
 * {@link McpTasksClient#onStatusNotification(com.nubian.ai.runtime.mcp.transport.McpTransport, String, java.util.function.Consumer)}
 * will receive these params.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskStatusNotificationParams(
        @JsonProperty("taskId") String taskId,
        @JsonProperty("status") TaskStatus status,
        @JsonProperty("statusMessage") String statusMessage,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("lastUpdatedAt") String lastUpdatedAt,
        @JsonProperty("ttl") Long ttl,
        @JsonProperty("pollInterval") Long pollInterval) {
}
