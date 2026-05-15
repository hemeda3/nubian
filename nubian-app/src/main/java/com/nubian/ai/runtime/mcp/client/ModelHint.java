package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A model name hint supplied by the server to guide the client's model selection.
 *
 * <p>Per the MCP spec (2025-11-25): hints are substrings matched flexibly against
 * available model names. The client resolves them to whichever actual model it has
 * available — {@code "claude-3-sonnet"} may map to a different model on a client
 * that doesn't have Claude. Hints are evaluated in order; the first match wins.
 *
 * @param name Model name substring (e.g. {@code "claude-3-sonnet"}, {@code "claude"}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelHint(
        @JsonProperty("name") String name) {
}
