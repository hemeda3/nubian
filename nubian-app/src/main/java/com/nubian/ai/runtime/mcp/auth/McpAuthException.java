package com.nubian.ai.runtime.mcp.auth;

/**
 * Runtime exception thrown by the MCP authorization layer when an OAuth flow
 * step fails (discovery, token exchange, validation, etc.).
 */
public class McpAuthException extends RuntimeException {

    public McpAuthException(String message) {
        super(message);
    }

    public McpAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
