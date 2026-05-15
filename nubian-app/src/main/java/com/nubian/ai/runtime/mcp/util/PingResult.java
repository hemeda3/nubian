package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a {@code ping} request.
 *
 * <p>Per the MCP spec (2025-11-25): the receiver MUST respond with an empty result
 * {@code {}}. This empty record serializes to exactly that.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PingResult() {
}
