package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result returned by the {@code resources/templates/list} JSON-RPC method.
 *
 * @param resourceTemplates The page of resource templates. Never null; may be empty.
 * @param nextCursor        Optional. If present, pass as cursor to retrieve the next page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListResourceTemplatesResult(
        List<ResourceTemplate> resourceTemplates,
        String nextCursor
) {
    public ListResourceTemplatesResult {
        if (resourceTemplates == null) {
            resourceTemplates = List.of();
        }
    }
}
