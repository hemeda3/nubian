package com.nubian.ai.runtime.mcp.transport;

/**
 * Thrown by {@link StreamableHttpTransport} when an HTTP-level connectivity error
 * occurs (e.g. connection refused, DNS resolution failure, TLS handshake failure).
 */
public class ConnectErrorException extends RuntimeException {

    public ConnectErrorException(String message) {
        super(message);
    }

    public ConnectErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
