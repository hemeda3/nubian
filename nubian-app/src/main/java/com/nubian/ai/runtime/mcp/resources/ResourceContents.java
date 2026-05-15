package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.nubian.ai.runtime.mcp.tools.Annotations;

import java.io.IOException;

/**
 * Sealed interface representing the contents of an MCP resource.
 *
 * <p>Per the MCP spec (2025-11-25), a resource contents object carries exactly one of:
 * <ul>
 *   <li>{@code text} — for text-based resources (UTF-8 string)</li>
 *   <li>{@code blob} — for binary resources (base-64 encoded string)</li>
 * </ul>
 *
 * <p>Deserialization is field-presence driven: if the JSON object contains a {@code text}
 * field the result is {@link TextResourceContents}; if it contains a {@code blob} field
 * the result is {@link BlobResourceContents}. A custom deserializer handles this
 * discrimination instead of a type-tag discriminator field.
 *
 * <p>Permitted variants: {@link TextResourceContents}, {@link BlobResourceContents}.
 */
@JsonDeserialize(using = ResourceContents.ResourceContentsDeserializer.class)
public sealed interface ResourceContents
        permits ResourceContents.TextResourceContents, ResourceContents.BlobResourceContents {

    /** The URI of the resource these contents belong to. */
    String uri();

    /** Optional MIME type of the content. */
    String mimeType();

    /**
     * Text resource contents. The {@code text} field carries the raw UTF-8 content.
     *
     * @param uri         Required. The resource URI.
     * @param mimeType    Optional. MIME type (e.g. {@code "text/plain"}).
     * @param text        Required. The text content (must not be null).
     * @param annotations Optional. Audience and priority hints.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TextResourceContents(
            String uri,
            String mimeType,
            String text,
            Annotations annotations
    ) implements ResourceContents {
        public TextResourceContents {
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("TextResourceContents.uri must not be blank");
            }
            if (text == null) {
                throw new IllegalArgumentException("TextResourceContents.text must not be null");
            }
        }
    }

    /**
     * Binary resource contents. The {@code blob} field carries base-64 encoded bytes.
     *
     * @param uri         Required. The resource URI.
     * @param mimeType    Optional. MIME type (e.g. {@code "image/png"}).
     * @param blob        Required. Base-64 encoded binary data (must not be null).
     * @param annotations Optional. Audience and priority hints.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BlobResourceContents(
            String uri,
            String mimeType,
            String blob,
            Annotations annotations
    ) implements ResourceContents {
        public BlobResourceContents {
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("BlobResourceContents.uri must not be blank");
            }
            if (blob == null) {
                throw new IllegalArgumentException("BlobResourceContents.blob must not be null");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Custom deserializer — picks variant based on field presence, not a type tag
    // -------------------------------------------------------------------------

    /**
     * Jackson deserializer that selects the {@link ResourceContents} variant by
     * inspecting which data field is present in the JSON object:
     * <ul>
     *   <li>Has {@code text} field  → {@link TextResourceContents}</li>
     *   <li>Has {@code blob} field  → {@link BlobResourceContents}</li>
     * </ul>
     * This matches the MCP spec exactly: there is no {@code type} discriminator field
     * on resource content objects.
     */
    final class ResourceContentsDeserializer extends StdDeserializer<ResourceContents> {

        ResourceContentsDeserializer() {
            super(ResourceContents.class);
        }

        @Override
        public ResourceContents deserialize(JsonParser p, DeserializationContext ctx)
                throws IOException {
            JsonNode node = p.getCodec().readTree(p);

            String uri       = node.hasNonNull("uri")       ? node.get("uri").asText()       : null;
            String mimeType  = node.hasNonNull("mimeType")  ? node.get("mimeType").asText()  : null;
            Annotations ann  = node.hasNonNull("annotations")
                    ? p.getCodec().treeToValue(node.get("annotations"), Annotations.class)
                    : null;

            if (node.hasNonNull("text")) {
                return new TextResourceContents(uri, mimeType, node.get("text").asText(), ann);
            }
            if (node.hasNonNull("blob")) {
                return new BlobResourceContents(uri, mimeType, node.get("blob").asText(), ann);
            }

            throw com.fasterxml.jackson.databind.exc.MismatchedInputException.from(
                    p, ResourceContents.class,
                    "Cannot determine ResourceContents variant: neither 'text' nor 'blob' field present");
        }
    }
}
