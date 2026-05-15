package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An embedded resource payload carried inline in a content block.
 *
 * <p>Per the MCP spec (2025-11-25): exactly one of {@code text} or {@code blob}
 * must be set — text resources carry UTF-8 text; binary resources carry base-64
 * encoded bytes in {@code blob}.
 *
 * @param uri         Resource URI (required).
 * @param mimeType    Optional MIME type of the resource content.
 * @param text        UTF-8 text content — mutually exclusive with {@code blob}.
 * @param blob        Base-64 encoded binary content — mutually exclusive with {@code text}.
 * @param annotations Optional audience / priority metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddedResource(
        @JsonProperty("uri") String uri,
        @JsonProperty("mimeType") String mimeType,
        @JsonProperty("text") String text,
        @JsonProperty("blob") String blob,
        @JsonProperty("annotations") Annotations annotations) {

    public EmbeddedResource {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("EmbeddedResource.uri must not be null or blank");
        }
        boolean hasText = text != null;
        boolean hasBlob = blob != null;
        if (hasText == hasBlob) {
            throw new IllegalArgumentException(
                    "EmbeddedResource: exactly one of 'text' or 'blob' must be set");
        }
    }

    /** Factory for a text resource. */
    public static EmbeddedResource ofText(String uri, String mimeType, String text) {
        return new EmbeddedResource(uri, mimeType, text, null, null);
    }

    /** Factory for a binary (base-64) resource. */
    public static EmbeddedResource ofBlob(String uri, String mimeType, String blob) {
        return new EmbeddedResource(uri, mimeType, null, blob, null);
    }
}
