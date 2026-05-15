package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Definition of an MCP tool exposed by a server.
 *
 * <p>Per the MCP spec (2025-11-25): tools are functions the LLM can call.
 * {@code inputSchema} MUST be a valid JSON Schema object (never null).
 *
 * <p>Name rules: 1–128 characters, {@code [A-Za-z0-9_.-]} only, case-sensitive, unique per server.
 *
 * @param name         Tool name (1–128 chars, {@code [A-Za-z0-9_.-]} only).
 * @param title        Optional human-readable display title.
 * @param description  Optional description of what the tool does.
 * @param inputSchema  JSON Schema for the tool's input parameters (required, never null).
 * @param outputSchema Optional JSON Schema validated against structured output.
 * @param annotations  Optional behavioral hints (readOnly, destructive, etc.).
 * @param execution    Optional task-augmentation policy.
 * @param icons        Optional icon list (kept as generic Map for forward compatibility).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("title") String title,
        @JsonProperty("description") String description,
        @JsonProperty("inputSchema") JsonNode inputSchema,
        @JsonProperty("outputSchema") JsonNode outputSchema,
        @JsonProperty("annotations") ToolAnnotations annotations,
        @JsonProperty("execution") ToolExecution execution,
        @JsonProperty("icons") List<Map<String, Object>> icons) {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]+$");

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolDefinition.name must not be null or blank");
        }
        if (name.length() > 128) {
            throw new IllegalArgumentException(
                    "ToolDefinition.name must be 1–128 characters, got length: " + name.length());
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "ToolDefinition.name must match [A-Za-z0-9_.-], got: " + name);
        }
        if (inputSchema == null) {
            throw new IllegalArgumentException("ToolDefinition.inputSchema must not be null");
        }
    }

    /** Convenience constructor — name, description, inputSchema only. */
    public ToolDefinition(String name, String description, JsonNode inputSchema) {
        this(name, null, description, inputSchema, null, null, null, null);
    }
}
