package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nubian.ai.runtime.mcp.protocol.ProgressToken;

/**
 * Parameters for a {@code notifications/progress} notification.
 *
 * <p>Per the MCP spec (2025-11-25):
 * <ul>
 *   <li>{@code progressToken} — matches the token supplied in the originating request's
 *       {@code _meta.progressToken}</li>
 *   <li>{@code progress} — current progress value; MUST increase monotonically</li>
 *   <li>{@code total} — optional upper bound (may be float, may be omitted)</li>
 *   <li>{@code message} — optional human-readable status string</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProgressNotificationParams(
        @JsonProperty("progressToken") ProgressToken progressToken,
        @JsonProperty("progress") double progress,
        @JsonProperty("total") Double total,
        @JsonProperty("message") String message) {

    public ProgressNotificationParams {
        if (progressToken == null) {
            throw new IllegalArgumentException("ProgressNotificationParams.progressToken must not be null");
        }
    }

    /** Convenience constructor — no total or message. */
    public ProgressNotificationParams(ProgressToken progressToken, double progress) {
        this(progressToken, progress, null, null);
    }
}
