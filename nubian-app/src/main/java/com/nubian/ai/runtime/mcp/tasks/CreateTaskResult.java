package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Optional;

/**
 * Result returned by a {@code tasks/create} call.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): the result carries the newly created
 * {@link Task} and an optional {@code _meta} map. The {@code _meta} map MAY contain the
 * key {@code "io.modelcontextprotocol/model-immediate-response"} whose value is the
 * server's model response that was produced synchronously before the task was queued.
 *
 * <p>Use {@link #extractImmediateResponse(CreateTaskResult)} to retrieve that value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateTaskResult(
        @JsonProperty("task") Task task,
        @JsonProperty("_meta") Map<String, Object> _meta) {

    /** Meta key for an immediate model response bundled with task creation. */
    public static final String META_IMMEDIATE_RESPONSE =
            "io.modelcontextprotocol/model-immediate-response";

    /**
     * Extracts the {@code io.modelcontextprotocol/model-immediate-response} entry from
     * the {@code _meta} map, if present.
     *
     * <p>The value is returned as a raw {@link Object}; callers are responsible for
     * casting to the appropriate type (typically a {@link JsonNode} or a
     * {@code Map&lt;String,Object&gt;} depending on the deserialiser in use).
     *
     * @param result the {@link CreateTaskResult} to inspect; must not be null
     * @return an {@link Optional} containing the immediate-response value, or empty
     */
    public static Optional<Object> extractImmediateResponse(CreateTaskResult result) {
        if (result == null || result._meta() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(result._meta().get(META_IMMEDIATE_RESPONSE));
    }
}
