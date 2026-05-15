package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.ProgressToken;

/**
 * Utility for opting a request into progress notifications.
 *
 * <p>Per the MCP spec (2025-11-25): a requestor opts in by including
 * {@code _meta.progressToken} in the request params. This class provides a
 * helper to attach a {@link ProgressToken} to an existing {@link ObjectNode}
 * params object.
 *
 * <p>No Spring annotations. Thread-safe (stateless).
 */
public final class ProgressOptIn {

    private ProgressOptIn() {}

    /**
     * Attaches {@code _meta.progressToken} to the given params {@link ObjectNode}.
     *
     * <p>If {@code _meta} already exists it is reused; otherwise a new object node
     * is created. Any existing {@code progressToken} key is overwritten.
     *
     * @param params the request params object to mutate (must not be null)
     * @param token  the progress token to attach (must not be null)
     * @return the same {@code params} node, mutated in-place, for chaining
     */
    public static ObjectNode withProgressToken(ObjectNode params, ProgressToken token) {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }

        ObjectNode meta;
        if (params.has("_meta") && params.get("_meta").isObject()) {
            meta = (ObjectNode) params.get("_meta");
        } else {
            meta = McpJsonMapper.instance().createObjectNode();
            params.set("_meta", meta);
        }

        Object raw = token.value();
        if (raw instanceof String s) {
            meta.put("progressToken", s);
        } else if (raw instanceof Long l) {
            meta.put("progressToken", l);
        } else {
            meta.put("progressToken", raw.toString());
        }

        return params;
    }

    /**
     * Overload that accepts a {@link com.nubian.ai.runtime.mcp.protocol.McpJsonMapper}
     * parameter for API symmetry with the spec description. Delegates to the stateless
     * overload — the mapper argument is ignored since no serialization is needed.
     *
     * @param params the request params object to mutate
     * @param token  the progress token to attach
     * @param mapper ignored; present for API symmetry
     * @return the same {@code params} node, mutated in-place
     */
    public static ObjectNode withProgressToken(
            ObjectNode params,
            ProgressToken token,
            com.nubian.ai.runtime.mcp.protocol.McpJsonMapper mapper) {
        return withProgressToken(params, token);
    }
}
