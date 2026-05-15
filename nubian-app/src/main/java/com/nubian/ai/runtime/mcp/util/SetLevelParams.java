package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parameters for the {@code logging/setLevel} request.
 *
 * <p>Per the MCP spec (2025-11-25): clients send this to tell the server the minimum
 * log level to emit. The server SHOULD suppress messages below the requested level.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetLevelParams(
        @JsonProperty("level") LogLevel level) {

    public SetLevelParams {
        if (level == null) {
            throw new IllegalArgumentException("SetLevelParams.level must not be null");
        }
    }
}
