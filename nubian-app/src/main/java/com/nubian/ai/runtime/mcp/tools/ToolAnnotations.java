package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Behavioral hints attached to a {@link ToolDefinition}.
 *
 * <p>Per the MCP spec (2025-11-25), these are informational — clients MAY use them to
 * surface confirmations or to decide whether to cache results. Servers SHOULD be
 * conservative (e.g. default {@code destructiveHint} to {@code true} when unknown).
 *
 * @param title           Human-readable label for the tool (may differ from {@code name}).
 * @param readOnlyHint    True if the tool does not modify external state.
 * @param destructiveHint True if the tool may perform destructive updates (default true).
 * @param idempotentHint  True if repeated calls with the same arguments have no additional effect.
 * @param openWorldHint   True if the tool may interact with entities outside the model context.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolAnnotations(
        @JsonProperty("title") String title,
        @JsonProperty("readOnlyHint") Boolean readOnlyHint,
        @JsonProperty("destructiveHint") Boolean destructiveHint,
        @JsonProperty("idempotentHint") Boolean idempotentHint,
        @JsonProperty("openWorldHint") Boolean openWorldHint) {
}
