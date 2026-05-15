package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Parameters for a {@code ping} request.
 *
 * <p>Per the MCP spec (2025-11-25): ping carries no parameters. This empty record
 * serializes to {@code {}} which is the correct JSON representation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PingParams() {
}
