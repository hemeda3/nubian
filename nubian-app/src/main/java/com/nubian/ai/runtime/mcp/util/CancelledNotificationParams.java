package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nubian.ai.runtime.mcp.protocol.RequestId;

/**
 * Parameters for the {@code notifications/cancelled} notification.
 *
 * <p>Per the MCP spec (2025-11-25): sent by either party to indicate that a previously
 * issued request should be cancelled. {@code requestId} identifies the request to cancel;
 * {@code reason} is optional human-readable text for debugging.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CancelledNotificationParams(
        @JsonProperty("requestId") RequestId requestId,
        @JsonProperty("reason") String reason) {

    public CancelledNotificationParams {
        if (requestId == null) {
            throw new IllegalArgumentException("CancelledNotificationParams.requestId must not be null");
        }
    }

    /** Convenience constructor — no reason string. */
    public CancelledNotificationParams(RequestId requestId) {
        this(requestId, null);
    }
}
