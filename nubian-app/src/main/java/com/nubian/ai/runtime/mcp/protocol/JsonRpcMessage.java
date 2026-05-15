package com.nubian.ai.runtime.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Sealed marker interface for all JSON-RPC 2.0 message types used in MCP
 * (Model Context Protocol, spec 2025-11-25).
 *
 * <p>Three permitted variants:
 * <ul>
 *   <li>{@link Request} — has both {@code id} and {@code method}</li>
 *   <li>{@link Response} — has {@code result} (success) or {@code error} (failure);
 *       itself a sealed interface permitting {@link Response.SuccessResponse} and
 *       {@link Response.ErrorResponse}</li>
 *   <li>{@link Notification} — has {@code method} but NO {@code id}</li>
 * </ul>
 *
 * <p>Polymorphic deserialization is driven by field presence (not a {@code @type}
 * discriminator) via {@link McpJsonMapper}. The {@code @JsonTypeInfo} /
 * {@code @JsonSubTypes} annotations here are provided as hints for frameworks that
 * inspect bean metadata; runtime dispatch is handled by
 * {@link McpJsonMapper.JsonRpcMessageDeserializer}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Serialize any message to JSON
 * String json = request.toJsonString();
 *
 * // Deserialize a raw JSON string to the correct subtype
 * JsonRpcMessage msg = McpJsonMapper.instance()
 *         .readValue(raw, JsonRpcMessage.class);
 * }</pre>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Request.class),
    @JsonSubTypes.Type(value = Response.SuccessResponse.class),
    @JsonSubTypes.Type(value = Response.ErrorResponse.class),
    @JsonSubTypes.Type(value = Notification.class)
})
public sealed interface JsonRpcMessage permits Request, Response, Notification {

    /**
     * Serialize this message to a compact JSON string using {@link McpJsonMapper}.
     *
     * @return JSON representation of this message
     * @throws IllegalStateException if serialization fails (should never happen for
     *         well-formed protocol types)
     */
    default String toJsonString() {
        try {
            return McpJsonMapper.instance().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + getClass().getSimpleName() + " to JSON", e);
        }
    }
}
