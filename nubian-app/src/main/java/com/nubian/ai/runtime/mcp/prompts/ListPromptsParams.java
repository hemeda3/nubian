package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Parameters for the {@code prompts/list} JSON-RPC method.
 *
 * <p>Per the MCP spec (2025-11-25 / prompts): the list operation is paginated via an
 * opaque {@code cursor} token. Omit (or pass {@code null}) on the first call;
 * supply the {@code nextCursor} from the previous response to page forward.
 *
 * @param cursor Optional. Opaque pagination cursor from a previous {@link ListPromptsResult}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListPromptsParams(String cursor) {

    /** Convenience constructor — first-page request (no cursor). */
    public static ListPromptsParams firstPage() {
        return new ListPromptsParams(null);
    }
}
