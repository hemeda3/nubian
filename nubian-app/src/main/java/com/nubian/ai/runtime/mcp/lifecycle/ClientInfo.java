package com.nubian.ai.runtime.mcp.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Describes the MCP client application.
 * Sent inside every {@code initialize} request.
 *
 * @param name        Required. Short identifier for the client (e.g. "Nubian").
 * @param title       Optional. Human-readable display name.
 * @param version     Required. Semantic version string (e.g. "0.1.0").
 * @param description Optional. Free-text description.
 * @param icons       Optional. List of icon assets for the client.
 * @param websiteUrl  Optional. URL for the client's website.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientInfo(
        String name,
        String title,
        String version,
        String description,
        List<Icon> icons,
        String websiteUrl
) {
    /**
     * Icon asset for the client or server.
     *
     * @param src      Required. URL or data-URI of the icon image.
     * @param mimeType Optional. MIME type (e.g. "image/png").
     * @param sizes    Optional. List of size descriptors (e.g. ["32x32", "64x64"]).
     * @param theme    Optional. Theme hint (e.g. "light", "dark").
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Icon(
            String src,
            String mimeType,
            List<String> sizes,
            String theme
    ) {
        public Icon {
            if (src == null || src.isBlank()) {
                throw new IllegalArgumentException("Icon.src must not be blank");
            }
        }
    }

    public ClientInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ClientInfo.name must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("ClientInfo.version must not be blank");
        }
    }
}
