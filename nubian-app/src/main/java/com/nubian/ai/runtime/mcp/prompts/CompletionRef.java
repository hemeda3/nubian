package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Discriminated reference that identifies the target of a {@code completion/complete} call.
 *
 * <p>Per the MCP spec (2025-11-25 / completion): the {@code ref} object carries a
 * {@code type} discriminator field. Two variants are defined:
 * <ul>
 *   <li>{@link PromptRef} ({@code "ref/prompt"}) — complete an argument of a named prompt.</li>
 *   <li>{@link ResourceTemplateRef} ({@code "ref/resource"}) — complete a URI template parameter.</li>
 * </ul>
 *
 * <p>Jackson polymorphic deserialization is driven by the {@code type} property.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = CompletionRef.PromptRef.class,           name = "ref/prompt"),
        @JsonSubTypes.Type(value = CompletionRef.ResourceTemplateRef.class, name = "ref/resource")
})
public sealed interface CompletionRef
        permits CompletionRef.PromptRef, CompletionRef.ResourceTemplateRef {

    /** The wire-level type discriminator (e.g. {@code "ref/prompt"}). */
    String type();

    /**
     * Reference to a prompt argument — used when the client wants completion
     * candidates for a specific argument of a named prompt.
     *
     * @param type Always {@code "ref/prompt"} on the wire.
     * @param name Required. Name of the prompt whose argument is being completed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PromptRef(
            @JsonProperty("type") String type,
            @JsonProperty("name") String name) implements CompletionRef {

        public PromptRef {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("PromptRef.name must not be blank");
            }
            if (type == null) {
                type = "ref/prompt";
            }
        }

        /** Convenience — sets {@code type} to {@code "ref/prompt"} automatically. */
        public PromptRef(String name) {
            this("ref/prompt", name);
        }
    }

    /**
     * Reference to a resource URI template parameter — used when the client wants
     * completion candidates for a parameter within a URI template.
     *
     * @param type Always {@code "ref/resource"} on the wire.
     * @param uri  Required. URI template string (e.g. {@code "file:///{path}"}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResourceTemplateRef(
            @JsonProperty("type") String type,
            @JsonProperty("uri")  String uri) implements CompletionRef {

        public ResourceTemplateRef {
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("ResourceTemplateRef.uri must not be blank");
            }
            if (type == null) {
                type = "ref/resource";
            }
        }

        /** Convenience — sets {@code type} to {@code "ref/resource"} automatically. */
        public ResourceTemplateRef(String uri) {
            this("ref/resource", uri);
        }
    }
}
