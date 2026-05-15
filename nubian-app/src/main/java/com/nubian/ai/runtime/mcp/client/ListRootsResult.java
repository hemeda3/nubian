package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Result returned by the client in response to a server-initiated {@code roots/list} request.
 *
 * <p>Per the MCP spec (2025-11-25, Roots): the server sends {@code roots/list} and
 * the client replies with this payload.
 *
 * @param roots The list of roots currently exposed by this client (never null; may be empty).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListRootsResult(
        @JsonProperty("roots") List<Root> roots) {

    public ListRootsResult {
        if (roots == null) {
            roots = List.of();
        }
    }
}
