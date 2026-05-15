package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result returned to the server after the client fulfils an {@code elicitation/create}
 * request.
 *
 * <p>Per the MCP spec (2025-11-25):
 * <ul>
 *   <li>{@link ElicitationAction#ACCEPT} + FORM mode: {@code content} carries the
 *       submitted form data as a JSON object matching the requested schema.</li>
 *   <li>{@link ElicitationAction#ACCEPT} + URL mode: {@code content} is omitted
 *       (data was collected out-of-band).</li>
 *   <li>{@link ElicitationAction#DECLINE} or {@link ElicitationAction#CANCEL}:
 *       {@code content} is typically omitted.</li>
 * </ul>
 *
 * @param action  The action taken by the user (required).
 * @param content Optional submitted data (present only for ACCEPT in FORM mode).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateElicitationResult(
        @JsonProperty("action") ElicitationAction action,
        @JsonProperty("content") JsonNode content) {

    public CreateElicitationResult {
        if (action == null) {
            throw new IllegalArgumentException(
                    "CreateElicitationResult.action must not be null");
        }
    }

    /** Convenience factory — action only, no content. */
    public static CreateElicitationResult of(ElicitationAction action) {
        return new CreateElicitationResult(action, null);
    }
}
