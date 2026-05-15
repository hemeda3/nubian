package com.nubian.ai.runtime.mcp.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Capabilities advertised by an MCP server in its {@code initialize} response.
 *
 * <p>Null fields are omitted from serialization. Presence of a field (even as an
 * empty record/map) signals that the server supports that capability.
 *
 * @param tools        Optional. Server can expose callable tool functions.
 * @param resources    Optional. Server can expose readable resources.
 * @param prompts      Optional. Server can expose prompt templates.
 * @param logging      Optional. Server can emit structured log events.
 * @param completions  Optional. Server supports argument auto-completion.
 * @param tasks        Optional. Server supports task-augmented requests.
 * @param experimental Optional. Non-standard extension capabilities.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerCapabilities(
        ToolsCapability tools,
        ResourcesCapability resources,
        PromptsCapability prompts,
        LoggingCapability logging,
        CompletionsCapability completions,
        TasksCapability tasks,
        Map<String, Object> experimental
) {

    /**
     * Server exposes callable tool functions.
     *
     * @param listChanged If true, the server sends {@code tools/listChanged}
     *                    notifications when its tool list changes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolsCapability(Boolean listChanged) {
    }

    /**
     * Server exposes readable resources.
     *
     * @param subscribe   If true, clients may subscribe to resource change events.
     * @param listChanged If true, the server sends {@code resources/listChanged}
     *                    notifications.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourcesCapability(Boolean subscribe, Boolean listChanged) {
    }

    /**
     * Server exposes prompt templates.
     *
     * @param listChanged If true, the server sends {@code prompts/listChanged}
     *                    notifications.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromptsCapability(Boolean listChanged) {
    }

    /**
     * Marker capability: server can emit structured log events to the client.
     * No sub-fields defined by the spec.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoggingCapability() {
    }

    /**
     * Marker capability: server supports argument auto-completion requests.
     * No sub-fields defined by the spec.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompletionsCapability() {
    }

    /**
     * Server supports task-augmented requests.
     *
     * @param list     Optional. Supports listing tasks.
     * @param cancel   Optional. Supports cancelling tasks.
     * @param requests Optional. Server-side task request capabilities (tools.call).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TasksCapability(
            Map<String, Object> list,
            Map<String, Object> cancel,
            TaskRequestsServer requests
    ) {
    }

    /**
     * Leaf-level task request capabilities on the server side.
     *
     * <p>The {@code tools} map is keyed by method name — e.g. {@code "call"} for
     * {@code tools.call} requests.
     *
     * @param tools Optional. Tool call task request methods the server accepts.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskRequestsServer(Map<String, Object> tools) {
    }
}
