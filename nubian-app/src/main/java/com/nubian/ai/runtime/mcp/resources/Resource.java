package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nubian.ai.runtime.mcp.lifecycle.ClientInfo.Icon;
import com.nubian.ai.runtime.mcp.tools.Annotations;

import java.util.List;

/**
 * A resource exposed by an MCP server.
 *
 * <p>Resources are read-only data items discoverable via {@code resources/list}.
 * Each resource is identified by a URI and carries optional metadata.
 *
 * @param uri         Required. The resource URI (must not be blank). Compliant with RFC 3986.
 * @param name        Required. Short identifier for the resource.
 * @param title       Optional. Human-readable display title.
 * @param description Optional. Free-text description.
 * @param mimeType    Optional. MIME type of the resource content.
 * @param size        Optional. Size in bytes, if known.
 * @param icons       Optional. Icon assets associated with this resource.
 * @param annotations Optional. Audience and priority hints for context management.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Resource(
        String uri,
        String name,
        String title,
        String description,
        String mimeType,
        Long size,
        List<Icon> icons,
        Annotations annotations
) {
    public Resource {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Resource.uri must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Resource.name must not be blank");
        }
    }
}
