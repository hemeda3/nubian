package com.nubian.ai.runtime.mcp.transport;

/**
 * Thrown by {@link StreamableHttpTransport} when the server returns HTTP 404
 * while an active MCP session is in progress, indicating the session has expired
 * or been evicted server-side.
 *
 * <p>Callers should re-initialize the MCP connection from scratch on receipt of
 * this exception.
 */
public class SessionExpiredException extends RuntimeException {

    private final String sessionId;

    public SessionExpiredException(String sessionId) {
        super("MCP session expired (server returned 404): sessionId=" + sessionId);
        this.sessionId = sessionId;
    }

    public SessionExpiredException(String sessionId, Throwable cause) {
        super("MCP session expired (server returned 404): sessionId=" + sessionId, cause);
        this.sessionId = sessionId;
    }

    /** The session ID that was active when the server rejected the request. */
    public String getSessionId() {
        return sessionId;
    }
}
