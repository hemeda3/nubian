package com.nubian.ai.runtime.mcp.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Describes the MCP server application.
 * Received inside the {@code initialize} response.
 *
 * @param name        Required. Short identifier for the server.
 * @param title       Optional. Human-readable display name.
 * @param version     Required. Semantic version string.
 * @param description Optional. Free-text description.
 * @param icons       Optional. List of icon assets for the server.
 * @param websiteUrl  Optional. URL for the server's website.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerInfo(
        String name,
        String title,
        String version,
        String description,
        List<ClientInfo.Icon> icons,
        String websiteUrl
) {
    public ServerInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ServerInfo.name must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("ServerInfo.version must not be blank");
        }
    }
}
