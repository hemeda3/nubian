package com.nubian.ai.runtime.mcp.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Capabilities advertised by this MCP client during {@code initialize}.
 *
 * <p>Null fields are omitted from JSON serialization (per spec: absence means not
 * supported). An empty map value (e.g. {@code sampling: {}}) means the capability
 * is supported but no sub-options are configured.
 *
 * @param roots        Optional. Client supports listing roots.
 * @param sampling     Optional. Client supports server-initiated LLM sampling calls.
 * @param elicitation  Optional. Client supports server-initiated user-input elicitation.
 * @param tasks        Optional. Client supports task-augmented requests.
 * @param experimental Optional. Non-standard extension capabilities.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientCapabilities(
        RootsCapability roots,
        SamplingCapability sampling,
        ElicitationCapability elicitation,
        TasksCapability tasks,
        Map<String, Object> experimental
) {

    /**
     * Signals that the client can provide filesystem roots and optionally
     * notifies the server when the root list changes.
     *
     * @param listChanged If true, the client sends {@code roots/listChanged}
     *                    notifications when the list changes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RootsCapability(Boolean listChanged) {
    }

    /**
     * Signals that the client supports server-initiated LLM sampling.
     *
     * <p>The {@code tools} and {@code context} maps are keyed by method name
     * (e.g. {@code "createMessage"}). An empty map means the method is supported
     * with no additional constraints.
     *
     * @param tools   Optional. Supported sampling tool methods.
     * @param context Optional. Supported context methods.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SamplingCapability(
            Map<String, Object> tools,
            Map<String, Object> context
    ) {
    }

    /**
     * Signals that the client supports server-initiated user-input elicitation.
     *
     * @param form Optional. Supports form-based elicitation (keyed by method name).
     * @param url  Optional. Supports URL-based elicitation (keyed by method name).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ElicitationCapability(
            Map<String, Object> form,
            Map<String, Object> url
    ) {
    }

    /**
     * Signals that the client supports task-augmented requests.
     *
     * @param list     Optional. Supports listing tasks.
     * @param cancel   Optional. Supports cancelling tasks.
     * @param requests Optional. Client-side task request capabilities (sampling,
     *                 elicitation).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TasksCapability(
            Map<String, Object> list,
            Map<String, Object> cancel,
            TaskRequestsClient requests
    ) {
    }

    /**
     * Leaf-level task request capabilities on the client side.
     *
     * <p>Each map is keyed by method name — e.g. {@code "createMessage"} for
     * sampling or {@code "create"} for elicitation.
     *
     * @param sampling    Optional. Sampling request methods the client accepts.
     * @param elicitation Optional. Elicitation request methods the client accepts.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskRequestsClient(
            Map<String, Object> sampling,
            Map<String, Object> elicitation
    ) {
    }
}
