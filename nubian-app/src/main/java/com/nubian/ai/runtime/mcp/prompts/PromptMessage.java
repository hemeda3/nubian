package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.nubian.ai.runtime.mcp.tools.ContentBlock;

/**
 * A single message in the resolved prompt returned by {@code prompts/get}.
 *
 * <p>Per the MCP spec (2025-11-25 / prompts): each message has a {@code role} and a
 * {@code content} block. Content may be any {@link ContentBlock} variant — text, image,
 * audio, or embedded resource.
 *
 * @param role    Required. Conversation role: {@link Role#USER} or {@link Role#ASSISTANT}.
 * @param content Required. Content block for this message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PromptMessage(
        Role role,
        ContentBlock content) {

    /** Canonical constructor — validates required fields. */
    public PromptMessage {
        if (role == null) {
            throw new IllegalArgumentException("PromptMessage.role must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("PromptMessage.content must not be null");
        }
    }

    /**
     * Conversation role for a {@link PromptMessage}.
     *
     * <p>Serializes to lower-case JSON strings per the MCP wire format:
     * {@code "user"} and {@code "assistant"}.
     */
    public enum Role {
        USER,
        ASSISTANT;

        /** Returns the lower-case wire representation. */
        @JsonValue
        public String toJson() {
            return name().toLowerCase();
        }
    }
}
