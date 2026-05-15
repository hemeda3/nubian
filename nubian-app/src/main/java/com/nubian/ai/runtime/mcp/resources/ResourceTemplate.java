package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nubian.ai.runtime.mcp.lifecycle.ClientInfo.Icon;
import com.nubian.ai.runtime.mcp.tools.Annotations;

import java.util.List;

/**
 * A parameterized URI template that clients can expand to construct resource URIs.
 *
 * <p>Discovered via {@code resources/templates/list}. The {@code uriTemplate} field
 * follows RFC 6570 Level 1 URI Template syntax (e.g. {@code "file:///{path}"}).
 *
 * @param uriTemplate Required. RFC 6570 URI template string.
 * @param name        Required. Short identifier for this template.
 * @param title       Optional. Human-readable display title.
 * @param description Optional. Free-text description.
 * @param mimeType    Optional. MIME type of resources produced by this template.
 * @param icons       Optional. Icon assets associated with this template.
 * @param annotations Optional. Audience and priority hints.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceTemplate(
        String uriTemplate,
        String name,
        String title,
        String description,
        String mimeType,
        List<Icon> icons,
        Annotations annotations
) {
    public ResourceTemplate {
        if (uriTemplate == null || uriTemplate.isBlank()) {
            throw new IllegalArgumentException("ResourceTemplate.uriTemplate must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ResourceTemplate.name must not be blank");
        }
    }
}
