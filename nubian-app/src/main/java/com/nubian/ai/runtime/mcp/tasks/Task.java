package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Represents a single MCP task as returned by the server.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section):
 * <ul>
 *   <li>{@code taskId} — server-assigned unique identifier; MUST be non-blank.</li>
 *   <li>{@code status} — current lifecycle status; MUST be non-null.</li>
 *   <li>{@code statusMessage} — optional human-readable status detail.</li>
 *   <li>{@code createdAt} — ISO 8601 creation timestamp; MUST be non-blank.</li>
 *   <li>{@code lastUpdatedAt} — ISO 8601 last-update timestamp; MUST be non-blank.</li>
 *   <li>{@code ttl} — remaining time-to-live in seconds; null when not set.</li>
 *   <li>{@code pollInterval} — suggested re-poll interval in milliseconds; null when not set.</li>
 *   <li>{@code _meta} — optional extension metadata map.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Task(
        @JsonProperty("taskId") String taskId,
        @JsonProperty("status") TaskStatus status,
        @JsonProperty("statusMessage") String statusMessage,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("lastUpdatedAt") String lastUpdatedAt,
        @JsonProperty("ttl") Long ttl,
        @JsonProperty("pollInterval") Long pollInterval,
        @JsonProperty("_meta") Map<String, Object> _meta) {

    /** Compact constructor — validates required fields. */
    public Task {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task.taskId must not be null or blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("Task.status must not be null");
        }
        if (createdAt == null || createdAt.isBlank()) {
            throw new IllegalArgumentException("Task.createdAt must not be null or blank");
        }
    }
}
