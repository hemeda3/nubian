package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Annotations that may be attached to any MCP content block, tool, or resource.
 *
 * <p>Per the MCP spec (2025-11-25): all primitive types (tools, resources, prompts,
 * content blocks) share a single annotation grammar.
 *
 * @param audience     Who the content is intended for: {@code "user"}, {@code "assistant"},
 *                     or both.
 * @param priority     0.0–1.0 importance for context inclusion (1.0 = required, 0.0 = optional).
 * @param lastModified ISO-8601 timestamp for cache invalidation and sorting.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Annotations(
        @JsonProperty("audience") List<String> audience,
        @JsonProperty("priority") Double priority,
        @JsonProperty("lastModified") String lastModified) {

    public Annotations {
        if (priority != null && (priority < 0.0 || priority > 1.0)) {
            throw new IllegalArgumentException(
                    "Annotations.priority must be in [0.0, 1.0], got: " + priority);
        }
    }
}
