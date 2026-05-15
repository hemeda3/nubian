package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result returned by the {@code resources/read} JSON-RPC method.
 *
 * <p>Each element in {@code contents} is either a {@link ResourceContents.TextResourceContents}
 * or a {@link ResourceContents.BlobResourceContents}.
 *
 * @param contents The resource content items. Never null; may be empty.
 * @param _meta    Optional. Arbitrary metadata from the server.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReadResourceResult(
        List<ResourceContents> contents,
        Map<String, Object> _meta
) {
    public ReadResourceResult {
        if (contents == null) {
            contents = List.of();
        }
    }
}
