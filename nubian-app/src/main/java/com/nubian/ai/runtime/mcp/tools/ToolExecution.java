package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Execution policy record attached to a {@link ToolDefinition}.
 *
 * <p>Declares whether the tool supports task-augmented invocation via the MCP tasks API.
 *
 * @param taskSupport Whether this tool supports, requires, or forbids task-augmented calls.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolExecution(
        @JsonProperty("taskSupport") TaskSupport taskSupport) {

    /**
     * Task-augmentation support level for a tool.
     *
     * <p>Values are serialized to lowercase JSON strings as required by the spec.
     */
    public enum TaskSupport {
        /** Tool must not be called with a task augmentation. */
        FORBIDDEN,
        /** Tool may optionally be called with a task augmentation. */
        OPTIONAL,
        /** Tool must always be called with a task augmentation. */
        REQUIRED;

        /** Serialize as lowercase string per MCP spec. */
        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }
}
