package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Result returned by the {@code completion/complete} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25 / completion): the response wraps a
 * {@link Completion} sub-object that holds the candidate values plus optional
 * pagination hints.
 *
 * @param completion Required. The completion candidates.
 * @param _meta      Optional. Protocol-level metadata (e.g. progress tokens).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompleteResult(
        Completion completion,
        @JsonProperty("_meta") Map<String, Object> _meta) {

    /** Canonical constructor — ensures {@code completion} is non-null. */
    public CompleteResult {
        if (completion == null) {
            throw new IllegalArgumentException("CompleteResult.completion must not be null");
        }
    }

    /** Convenience: result with no meta. */
    public CompleteResult(Completion completion) {
        this(completion, null);
    }

    /**
     * Completion candidates returned by the server.
     *
     * @param values  Required. Up to 100 completion string candidates (server may truncate).
     * @param total   Optional. Total number of candidates that exist (may exceed {@code values.size()}).
     * @param hasMore Optional. {@code true} when more candidates exist beyond those returned.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Completion(
            List<String> values,
            Integer total,
            Boolean hasMore) {

        /** Canonical constructor — ensures {@code values} is non-null. */
        public Completion {
            if (values == null) {
                throw new IllegalArgumentException("Completion.values must not be null");
            }
        }

        /** Convenience: values only, no pagination hints. */
        public Completion(List<String> values) {
            this(values, null, null);
        }
    }
}
