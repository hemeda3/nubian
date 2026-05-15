package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface representing any MCP content block.
 *
 * <p>Per the MCP spec (2025-11-25), tool results, sampling messages, and embedded
 * resources carry typed content blocks. Jackson polymorphic dispatch is driven by
 * the {@code "type"} field.
 *
 * <p>Permitted variants:
 * <ul>
 *   <li>{@link TextContent} — plain text</li>
 *   <li>{@link ImageContent} — base-64 encoded image</li>
 *   <li>{@link AudioContent} — base-64 encoded audio</li>
 *   <li>{@link ResourceLinkContent} — pointer to a resource (URI reference)</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.TextContent.class,             name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.ImageContent.class,            name = "image"),
        @JsonSubTypes.Type(value = ContentBlock.AudioContent.class,            name = "audio"),
        @JsonSubTypes.Type(value = ContentBlock.ResourceLinkContent.class,     name = "resource_link"),
        @JsonSubTypes.Type(value = ContentBlock.EmbeddedResourceContent.class, name = "resource"),
})
public sealed interface ContentBlock
        permits ContentBlock.TextContent,
                ContentBlock.ImageContent,
                ContentBlock.AudioContent,
                ContentBlock.ResourceLinkContent,
                ContentBlock.EmbeddedResourceContent {

    /** Returns the {@code type} discriminator string. */
    String type();

    // ------------------------------------------------------------------
    // TextContent
    // ------------------------------------------------------------------

    /**
     * A plain-text content block.
     *
     * @param text        The text payload (required).
     * @param annotations Optional audience / priority metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TextContent(
            @JsonProperty("text") String text,
            @JsonProperty("annotations") Annotations annotations) implements ContentBlock {

        public TextContent {
            if (text == null) {
                throw new IllegalArgumentException("TextContent.text must not be null");
            }
        }

        /** Convenience constructor — no annotations. */
        public TextContent(String text) {
            this(text, null);
        }

        @Override
        public String type() {
            return "text";
        }
    }

    // ------------------------------------------------------------------
    // ImageContent
    // ------------------------------------------------------------------

    /**
     * A base-64 encoded image content block.
     *
     * @param data        Base-64 encoded image data (required).
     * @param mimeType    MIME type, e.g. {@code "image/png"} (required).
     * @param annotations Optional audience / priority metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ImageContent(
            @JsonProperty("data") String data,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("annotations") Annotations annotations) implements ContentBlock {

        public ImageContent {
            if (data == null) {
                throw new IllegalArgumentException("ImageContent.data must not be null");
            }
            if (mimeType == null) {
                throw new IllegalArgumentException("ImageContent.mimeType must not be null");
            }
        }

        /** Convenience constructor — no annotations. */
        public ImageContent(String data, String mimeType) {
            this(data, mimeType, null);
        }

        @Override
        public String type() {
            return "image";
        }
    }

    // ------------------------------------------------------------------
    // AudioContent
    // ------------------------------------------------------------------

    /**
     * A base-64 encoded audio content block.
     *
     * @param data        Base-64 encoded audio data (required).
     * @param mimeType    MIME type, e.g. {@code "audio/wav"} (required).
     * @param annotations Optional audience / priority metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AudioContent(
            @JsonProperty("data") String data,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("annotations") Annotations annotations) implements ContentBlock {

        public AudioContent {
            if (data == null) {
                throw new IllegalArgumentException("AudioContent.data must not be null");
            }
            if (mimeType == null) {
                throw new IllegalArgumentException("AudioContent.mimeType must not be null");
            }
        }

        /** Convenience constructor — no annotations. */
        public AudioContent(String data, String mimeType) {
            this(data, mimeType, null);
        }

        @Override
        public String type() {
            return "audio";
        }
    }

    // ------------------------------------------------------------------
    // ResourceLinkContent
    // ------------------------------------------------------------------

    /**
     * A pointer to a fetchable MCP resource — avoids inlining large payloads.
     *
     * @param uri         Resource URI (required).
     * @param name        Human-readable name (optional).
     * @param description Optional description.
     * @param mimeType    Optional MIME type hint.
     * @param annotations Optional audience / priority metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResourceLinkContent(
            @JsonProperty("uri") String uri,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("annotations") Annotations annotations) implements ContentBlock {

        public ResourceLinkContent {
            if (uri == null) {
                throw new IllegalArgumentException("ResourceLinkContent.uri must not be null");
            }
        }

        /** Convenience constructor — uri only. */
        public ResourceLinkContent(String uri) {
            this(uri, null, null, null, null);
        }

        @Override
        public String type() {
            return "resource_link";
        }
    }

    // ------------------------------------------------------------------
    // EmbeddedResourceContent
    // ------------------------------------------------------------------

    /**
     * An inline embedded resource carried directly in the content block.
     *
     * <p>Per the MCP spec (2025-11-25), type discriminator is {@code "resource"}.
     *
     * @param resource    The embedded resource payload (required).
     * @param annotations Optional audience / priority metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EmbeddedResourceContent(
            @JsonProperty("resource") EmbeddedResource resource,
            @JsonProperty("annotations") Annotations annotations) implements ContentBlock {

        public EmbeddedResourceContent {
            if (resource == null) {
                throw new IllegalArgumentException("EmbeddedResourceContent.resource must not be null");
            }
        }

        /** Convenience constructor — resource only. */
        public EmbeddedResourceContent(EmbeddedResource resource) {
            this(resource, null);
        }

        @Override
        public String type() {
            return "resource";
        }
    }
}
