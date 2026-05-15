package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Instructs the model how to behave with respect to tool calls in a sampling request.
 *
 * @param mode The tool-call discipline (required).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolChoice(
        @JsonProperty("mode") Mode mode) {

    /**
     * Tool-call mode.
     */
    public enum Mode {
        /** Model decides whether to call tools (default). */
        AUTO,
        /** Model MUST call at least one tool. */
        REQUIRED,
        /** Model MUST NOT call tools — forces a final answer. */
        NONE;

        @JsonValue
        public String jsonValue() {
            return name().toLowerCase();
        }
    }
}
