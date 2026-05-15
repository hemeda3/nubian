package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A filesystem root exposed by this MCP client to a connected server.
 *
 * <p>Per the MCP spec (2025-11-25, Roots): the client declares which directories
 * or URIs are in-bounds. Servers MUST respect these boundaries and validate all
 * paths against the root list to defend against path-traversal attacks.
 *
 * @param uri  The root URI. MUST start with {@code "file://"}.
 * @param name Optional human-readable label shown in server UIs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Root(
        @JsonProperty("uri") String uri,
        @JsonProperty("name") String name) {

    public Root {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Root.uri must not be null or blank");
        }
        if (!uri.startsWith("file://")) {
            throw new IllegalArgumentException(
                    "Root.uri must start with \"file://\" but was: " + uri);
        }
    }

    /** Convenience constructor — uri only, no display name. */
    public Root(String uri) {
        this(uri, null);
    }
}
