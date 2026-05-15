package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parameters for the optional {@code notifications/elicitation/complete} notification
 * sent by the server after a URL-mode elicitation flow has finished.
 *
 * <p>Per the MCP spec (2025-11-25): when the server receives a callback from the
 * out-of-band URL flow (e.g. OAuth redirect), it MAY emit this notification so the
 * client knows the elicitation has been resolved and can retry the original request.
 *
 * @param elicitationId The id of the elicitation that has been completed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ElicitationCompleteNotificationParams(
        @JsonProperty("elicitationId") String elicitationId) {

    public ElicitationCompleteNotificationParams {
        if (elicitationId == null || elicitationId.isBlank()) {
            throw new IllegalArgumentException(
                    "ElicitationCompleteNotificationParams.elicitationId must not be null or blank");
        }
    }
}
