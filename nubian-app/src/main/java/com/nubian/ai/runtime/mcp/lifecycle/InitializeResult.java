package com.nubian.ai.runtime.mcp.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Result returned by the server in response to an MCP {@code initialize} request.
 *
 * <p>Deserializes from the {@code result} field of the JSON-RPC 2.0 response.
 *
 * @param protocolVersion The protocol version the server chose. The client must
 *                        verify this is in {@link ProtocolVersion#SUPPORTED_SET};
 *                        if not, it must disconnect per the spec.
 * @param capabilities    The capabilities this server supports.
 * @param serverInfo      Metadata about the server application.
 * @param instructions    Optional. Human-readable instructions for the LLM on how
 *                        to interact with this server.
 * @param meta            Optional. Reserved {@code _meta} field for protocol-level
 *                        metadata (e.g. progress tokens).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InitializeResult(
        String protocolVersion,
        ServerCapabilities capabilities,
        ServerInfo serverInfo,
        String instructions,
        @JsonProperty("_meta") Map<String, Object> meta
) {
    public InitializeResult {
        if (protocolVersion == null || protocolVersion.isBlank()) {
            throw new IllegalArgumentException("InitializeResult.protocolVersion must not be blank");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("InitializeResult.capabilities must not be null");
        }
        if (serverInfo == null) {
            throw new IllegalArgumentException("InitializeResult.serverInfo must not be null");
        }
    }
}
