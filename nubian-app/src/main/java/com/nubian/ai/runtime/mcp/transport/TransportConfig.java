package com.nubian.ai.runtime.mcp.transport;

import java.time.Duration;

/**
 * Immutable configuration for MCP transports.
 *
 * @param connectTimeout      How long to wait for the initial TCP/process connection.
 * @param requestTimeout      How long to wait for a response to a single JSON-RPC request.
 * @param maxReconnectAttempts Maximum number of reconnect attempts on the SSE server stream before giving up.
 * @param reconnectBackoff    Base back-off duration between reconnect attempts (may be multiplied by attempt count).
 */
public record TransportConfig(
        Duration connectTimeout,
        Duration requestTimeout,
        int maxReconnectAttempts,
        Duration reconnectBackoff) {

    /** Sensible defaults for typical MCP server interactions. */
    public static final TransportConfig DEFAULT = new TransportConfig(
            Duration.ofSeconds(10),
            Duration.ofSeconds(60),
            5,
            Duration.ofSeconds(2));

    public TransportConfig {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxReconnectAttempts < 0) {
            throw new IllegalArgumentException("maxReconnectAttempts must be >= 0");
        }
        if (reconnectBackoff == null || reconnectBackoff.isNegative()) {
            throw new IllegalArgumentException("reconnectBackoff must be non-negative");
        }
    }
}
