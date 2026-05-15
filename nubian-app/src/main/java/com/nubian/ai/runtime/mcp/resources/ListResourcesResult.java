package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result returned by the {@code resources/list} JSON-RPC method.
 *
 * @param resources   The page of resources. Never null; may be empty.
 * @param nextCursor  Optional. If present, pass as cursor to retrieve the next page.
 * @param _meta       Optional. Arbitrary metadata (e.g. progress tokens) from the server.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListResourcesResult(
        List<Resource> resources,
        String nextCursor,
        Map<String, Object> _meta
) {
    public ListResourcesResult {
        if (resources == null) {
            resources = List.of();
        }
    }
}
