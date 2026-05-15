package com.nubian.ai.runtime.mcp.lifecycle;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Parameters for the MCP {@code initialize} request (client → server).
 *
 * <p>Serializes to the {@code params} field of a JSON-RPC 2.0 request whose
 * {@code method} is {@code "initialize"}.
 *
 * @param protocolVersion The MCP protocol version the client is requesting.
 *                        Should be {@link ProtocolVersion#CURRENT}.
 * @param capabilities    The capabilities this client supports.
 * @param clientInfo      Metadata about this client application.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InitializeParams(
        String protocolVersion,
        ClientCapabilities capabilities,
        ClientInfo clientInfo
) {
    public InitializeParams {
        if (protocolVersion == null || protocolVersion.isBlank()) {
            throw new IllegalArgumentException("InitializeParams.protocolVersion must not be blank");
        }
        if (capabilities == null) {
            throw new IllegalArgumentException("InitializeParams.capabilities must not be null");
        }
        if (clientInfo == null) {
            throw new IllegalArgumentException("InitializeParams.clientInfo must not be null");
        }
    }
}
