package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for the MCP {@code _meta} field and reserved-prefix validation.
 *
 * <p>Per the MCP spec (2025-11-25):
 * <ul>
 *   <li>{@code _meta} carries protocol-level metadata, not application data.</li>
 *   <li>Reserved prefixes: {@code modelcontextprotocol/} and {@code mcp/} (as
 *       identified by the second label of a reverse-DNS URI being
 *       {@code modelcontextprotocol} or {@code mcp}).</li>
 *   <li>The {@code io.modelcontextprotocol/related-task} key carries a
 *       {@code taskId} that links a request/response/notification to a task.</li>
 * </ul>
 *
 * <p>No Spring annotations. Thread-safe (stateless).
 */
public final class McpMeta {

    private static final Logger log = LoggerFactory.getLogger(McpMeta.class);

    /** The {@code _meta} key used to annotate related-task context. */
    public static final String RELATED_TASK_KEY = "io.modelcontextprotocol/related-task";

    private McpMeta() {}

    // -------------------------------------------------------------------------
    // Reserved-prefix validation
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given {@code _meta} key uses a reserved MCP prefix.
     *
     * <p>The reserved prefixes are those whose URI (interpreted as a reverse-DNS label)
     * has a second label of {@code modelcontextprotocol} or {@code mcp}.  Examples:
     * <ul>
     *   <li>{@code io.modelcontextprotocol/related-task} — reserved</li>
     *   <li>{@code com.modelcontextprotocol/anything} — reserved</li>
     *   <li>{@code io.mcp/anything} — reserved</li>
     *   <li>{@code com.example/custom} — not reserved</li>
     *   <li>{@code progressToken} — not reserved (no dot separator)</li>
     * </ul>
     *
     * @param key the {@code _meta} key to test (must not be null)
     * @return {@code true} if the key uses a reserved prefix
     */
    public static boolean isReservedMetaKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("_meta key must not be null");
        }
        // Split on the first '/' to get the URI authority portion, then split on '.'
        // to extract the second DNS label (labels are ordered: TLD.second.third…).
        int slashIdx = key.indexOf('/');
        String authority = slashIdx >= 0 ? key.substring(0, slashIdx) : key;
        String[] labels = authority.split("\\.");
        if (labels.length < 2) {
            return false;
        }
        // Second label from the left is index 1 in the reverse-DNS array
        // (e.g. "io.modelcontextprotocol" → labels[0]="io", labels[1]="modelcontextprotocol")
        String secondLabel = labels[1].toLowerCase();
        return "modelcontextprotocol".equals(secondLabel) || "mcp".equals(secondLabel);
    }

    /**
     * Asserts that the given {@code _meta} key is not reserved.
     *
     * @param key the key to validate
     * @throws IllegalArgumentException if the key uses a reserved prefix
     */
    public static void assertNotReserved(String key) {
        if (isReservedMetaKey(key)) {
            throw new IllegalArgumentException(
                    "Attempt to write reserved _meta key '" + key
                            + "'. Keys with 'modelcontextprotocol' or 'mcp' as the second "
                            + "reverse-DNS label are reserved for the MCP protocol.");
        }
    }

    // -------------------------------------------------------------------------
    // Related-task extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts the {@code taskId} from the {@code io.modelcontextprotocol/related-task}
     * entry in a {@code _meta} {@link JsonNode}.
     *
     * <p>Expected structure:
     * <pre>{@code
     * {
     *   "io.modelcontextprotocol/related-task": { "taskId": "786512e2-..." }
     * }
     * }</pre>
     *
     * @param meta a {@link JsonNode} representing the {@code _meta} object (may be null
     *             or missing fields — returns {@code null} gracefully in those cases)
     * @return the {@code taskId} string, or {@code null} if absent or malformed
     */
    public static String extractRelatedTaskId(JsonNode meta) {
        if (meta == null || meta.isNull() || !meta.isObject()) {
            return null;
        }
        JsonNode relatedTask = meta.get(RELATED_TASK_KEY);
        if (relatedTask == null || relatedTask.isNull() || !relatedTask.isObject()) {
            return null;
        }
        JsonNode taskIdNode = relatedTask.get("taskId");
        if (taskIdNode == null || taskIdNode.isNull() || !taskIdNode.isTextual()) {
            return null;
        }
        return taskIdNode.asText();
    }
}
