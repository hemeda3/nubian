package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Carries task lifecycle metadata that may be attached to any MCP request.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): a client MAY include a {@code task}
 * field in the {@code params} object of any request to associate that request with a
 * task. The field is a {@link TaskAugmentation} object.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code ttl} — optional time-to-live in seconds; when present MUST be &ge; 0.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskAugmentation(
        @JsonProperty("ttl") Long ttl) {

    /** Compact constructor — validates ttl is non-negative when present. */
    public TaskAugmentation {
        if (ttl != null && ttl < 0) {
            throw new IllegalArgumentException("TaskAugmentation.ttl must be >= 0, got: " + ttl);
        }
    }
}
