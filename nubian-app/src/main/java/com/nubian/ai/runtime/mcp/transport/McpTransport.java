package com.nubian.ai.runtime.mcp.transport;

import com.nubian.ai.runtime.mcp.protocol.JsonRpcMessage;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.Response;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Abstraction over an MCP transport channel (Streamable HTTP, stdio, etc.).
 *
 * <p>Implementations are responsible for framing, serialization, and request/response
 * correlation. This interface is produced by slice 1; this stub allows dependent slices
 * to compile in parallel.
 */
public interface McpTransport {

    /**
     * Send a JSON-RPC {@link Request} and return the matching {@link Response}
     * asynchronously. The transport correlates by {@code id}.
     */
    CompletableFuture<Response> send(Request request);

    /**
     * Convenience alias used by higher-level managers. Delegates to {@link #send(Request)}.
     */
    default CompletableFuture<Response> sendRequest(Request request) {
        return send(request);
    }

    /**
     * Send a fire-and-forget {@link Notification} (no response expected).
     *
     * @param notification the notification to send
     */
    void sendNotification(Notification notification);

    /**
     * Close the transport and release any underlying resources.
     */
    void close();

    /**
     * Register a handler that will be called for every incoming {@link JsonRpcMessage}
     * that is NOT a response to a pending request (i.e. server-initiated notifications
     * and requests).
     *
     * <p>Multiple handlers may be registered; all are called in registration order.
     *
     * @param handler consumer invoked on the calling transport thread
     */
    void onIncoming(Consumer<JsonRpcMessage> handler);
}
