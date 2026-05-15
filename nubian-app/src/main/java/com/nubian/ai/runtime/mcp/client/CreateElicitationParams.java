package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parameters for an {@code elicitation/create} request sent by the server.
 *
 * <p>Per the MCP spec (2025-11-25): servers use elicitation to collect user input that
 * cannot be inferred from context. Two modes:
 * <ul>
 *   <li>{@link ElicitationMode#FORM} — structured data via JSON Schema, rendered inline
 *       by the client. {@code requestedSchema} is allowed but optional (servers may omit
 *       it for unstructured free-text prompts).</li>
 *   <li>{@link ElicitationMode#URL} — sensitive interaction handled in an external
 *       browser. Both {@code url} and {@code elicitationId} are REQUIRED for URL mode.</li>
 * </ul>
 *
 * @param mode            The elicitation mode (required).
 * @param message         Human-readable prompt shown to the user (required).
 * @param requestedSchema JSON Schema for form mode (nullable; FORM only).
 * @param url             The URL to open in URL mode (required for URL mode; nullable otherwise).
 * @param elicitationId   Correlation id for the URL-mode flow (required for URL mode;
 *                        nullable otherwise).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateElicitationParams(
        @JsonProperty("mode") ElicitationMode mode,
        @JsonProperty("message") String message,
        @JsonProperty("requestedSchema") JsonNode requestedSchema,
        @JsonProperty("url") String url,
        @JsonProperty("elicitationId") String elicitationId) {

    public CreateElicitationParams {
        if (mode == null) {
            throw new IllegalArgumentException(
                    "CreateElicitationParams.mode must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "CreateElicitationParams.message must not be null or blank");
        }
        if (mode == ElicitationMode.URL) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException(
                        "CreateElicitationParams.url is required for URL mode");
            }
            if (elicitationId == null || elicitationId.isBlank()) {
                throw new IllegalArgumentException(
                        "CreateElicitationParams.elicitationId is required for URL mode");
            }
        }
        // FORM mode: requestedSchema is optional (unusual but allowed per spec)
    }
}
