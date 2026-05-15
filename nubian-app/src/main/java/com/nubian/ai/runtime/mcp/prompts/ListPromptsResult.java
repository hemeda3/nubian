package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result returned by the {@code prompts/list} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25 / prompts): the response carries the current page of
 * prompts and an optional {@code nextCursor} token for retrieving subsequent pages.
 *
 * @param prompts    Required. Page of prompts (may be empty but not null).
 * @param nextCursor Optional. Opaque cursor; present when more pages are available.
 * @param _meta      Optional. Protocol-level metadata (e.g. progress tokens).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListPromptsResult(
        List<Prompt> prompts,
        String nextCursor,
        @JsonProperty("_meta") Map<String, Object> _meta) {

    /** Canonical constructor — ensures {@code prompts} list is non-null. */
    public ListPromptsResult {
        if (prompts == null) {
            throw new IllegalArgumentException("ListPromptsResult.prompts must not be null");
        }
    }

    /** Convenience: result with prompts only (no pagination, no meta). */
    public ListPromptsResult(List<Prompt> prompts) {
        this(prompts, null, null);
    }

    /** Returns {@code true} when a {@link #nextCursor} is present (more pages available). */
    public boolean hasMore() {
        return nextCursor != null && !nextCursor.isBlank();
    }
}
