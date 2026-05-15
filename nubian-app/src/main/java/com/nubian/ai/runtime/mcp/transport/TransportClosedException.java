package com.nubian.ai.runtime.mcp.transport;

/**
 * Thrown when an operation is attempted on a transport that has already been closed,
 * or when the transport closes while requests are in flight (all pending futures are
 * completed exceptionally with this exception).
 */
public class TransportClosedException extends RuntimeException {

    public TransportClosedException() {
        super("MCP transport has been closed");
    }

    public TransportClosedException(String message) {
        super(message);
    }

    public TransportClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
