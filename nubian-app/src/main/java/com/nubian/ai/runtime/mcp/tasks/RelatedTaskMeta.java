package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * Utility for reading and writing the
 * {@code io.modelcontextprotocol/related-task} entry inside an MCP {@code _meta} map.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): any MCP message MAY carry a
 * {@code _meta} object. When a message is associated with an existing task the
 * {@code _meta} object SHOULD include the key
 * {@code "io.modelcontextprotocol/related-task"} whose value is the task identifier
 * string.
 *
 * <p>Usage example:
 * <pre>{@code
 * ObjectNode meta = mapper.createObjectNode();
 * RelatedTaskMeta.withTaskId(meta, "task-abc-123");
 *
 * Optional<String> taskId = RelatedTaskMeta.extractTaskId(meta);
 * }</pre>
 */
public final class RelatedTaskMeta {

    /** The {@code _meta} key defined by the MCP spec for related-task association. */
    public static final String META_KEY = "io.modelcontextprotocol/related-task";

    private RelatedTaskMeta() {
        // utility class — not instantiable
    }

    /**
     * Adds (or overwrites) the {@code io.modelcontextprotocol/related-task} entry in
     * the given {@link ObjectNode}.
     *
     * @param meta   the {@code _meta} object to mutate; must not be {@code null}
     * @param taskId the task identifier to store; must not be {@code null} or blank
     * @return the same {@code meta} node for chaining
     */
    public static ObjectNode withTaskId(ObjectNode meta, String taskId) {
        if (meta == null) {
            throw new IllegalArgumentException("meta must not be null");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be null or blank");
        }
        meta.put(META_KEY, taskId);
        return meta;
    }

    /**
     * Extracts the task identifier from the {@code io.modelcontextprotocol/related-task}
     * field of the given {@link JsonNode}.
     *
     * @param meta a {@link JsonNode} representing the {@code _meta} object; may be
     *             {@code null} or {@link JsonNode#isMissingNode()}
     * @return an {@link Optional} containing the task ID string, or empty when the field
     *         is absent, null, or not a text node
     */
    public static Optional<String> extractTaskId(JsonNode meta) {
        if (meta == null || meta.isNull() || meta.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode node = meta.get(META_KEY);
        if (node == null || node.isNull() || !node.isTextual()) {
            return Optional.empty();
        }
        return Optional.of(node.asText());
    }
}
