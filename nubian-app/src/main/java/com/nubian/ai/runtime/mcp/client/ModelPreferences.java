package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Server-supplied guidance on which model the client should select for a sampling call.
 *
 * <p>Per the MCP spec (2025-11-25): model preferences use an abstraction layer so servers
 * don't have to know which specific models a client has available. Priorities are
 * normalized values in [0.0, 1.0]. Hints are evaluated in order; first match wins. The
 * client is free to ignore all hints and fall back to its defaults.
 *
 * @param hints               Ordered list of model name substrings to try (nullable).
 * @param costPriority        Weight for cost (0.0 = ignore, 1.0 = top priority; nullable).
 * @param speedPriority       Weight for speed (nullable).
 * @param intelligencePriority Weight for capability/quality (nullable).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelPreferences(
        @JsonProperty("hints") List<ModelHint> hints,
        @JsonProperty("costPriority") Double costPriority,
        @JsonProperty("speedPriority") Double speedPriority,
        @JsonProperty("intelligencePriority") Double intelligencePriority) {

    public ModelPreferences {
        validatePriority("costPriority", costPriority);
        validatePriority("speedPriority", speedPriority);
        validatePriority("intelligencePriority", intelligencePriority);
    }

    private static void validatePriority(String fieldName, Double value) {
        if (value != null && (value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException(
                    "ModelPreferences." + fieldName + " must be in [0.0, 1.0] but was: " + value);
        }
    }
}
