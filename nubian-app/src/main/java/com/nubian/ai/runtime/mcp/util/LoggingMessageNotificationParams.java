package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parameters carried by a {@code notifications/message} notification from the server.
 *
 * <p>Per the MCP spec (2025-11-25): the server sends these to emit log events to the
 * client. {@code level} is the RFC 5424 severity; {@code logger} identifies the
 * component (optional); {@code data} is the structured log payload (any JSON value).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoggingMessageNotificationParams(
        @JsonProperty("level") LogLevel level,
        @JsonProperty("logger") String logger,
        @JsonProperty("data") JsonNode data) {

    public LoggingMessageNotificationParams {
        if (level == null) {
            throw new IllegalArgumentException("LoggingMessageNotificationParams.level must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("LoggingMessageNotificationParams.data must not be null");
        }
    }
}
