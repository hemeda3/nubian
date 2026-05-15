package com.nubian.ai.runtime.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.nubian.ai.runtime.mcp.protocol.JsonRpcMessage;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.protocol.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Abstract base class implementing common {@link McpTransport} mechanics:
 * request-id allocation, pending-request tracking, and incoming-message dispatch.
 *
 * <p>Subclasses must implement:
 * <ul>
 *   <li>{@link #sendRaw(String)} — write a serialized JSON string to the wire</li>
 *   <li>{@link #startReadLoop()} — begin consuming incoming messages and calling
 *       {@link #dispatchIncoming(JsonRpcMessage)}</li>
 * </ul>
 */
public abstract class AbstractMcpTransport implements McpTransport {

    private static final Logger logger = LoggerFactory.getLogger(AbstractMcpTransport.class);

    /** Pending outbound requests awaiting a matching response. */
    protected final ConcurrentHashMap<RequestId, CompletableFuture<Response>> pendingRequests =
            new ConcurrentHashMap<>();

    /** Monotonically increasing counter for auto-allocated Long request IDs. */
    private final AtomicLong requestIdCounter = new AtomicLong(0);

    /** Registered server-push / notification handlers (in registration order). */
    private final CopyOnWriteArrayList<Consumer<JsonRpcMessage>> incomingHandlers =
            new CopyOnWriteArrayList<>();

    /** Set to {@code true} after {@link #close()} is called. */
    protected volatile boolean closed = false;

    // ------------------------------------------------------------------
    // McpTransport implementation
    // ------------------------------------------------------------------

    /**
     * Allocates a new {@link RequestId.LongId}, registers a pending future,
     * serializes the request, and calls {@link #sendRaw(String)}.
     */
    @Override
    public CompletableFuture<Response> send(Request request) {
        if (closed) {
            return CompletableFuture.failedFuture(new TransportClosedException());
        }

        RequestId id = request.id();
        CompletableFuture<Response> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        String json;
        try {
            json = McpJsonMapper.instance().writeValueAsString(request);
        } catch (JsonProcessingException e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
            return future;
        }

        try {
            sendRaw(json);
        } catch (IOException e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Serializes the {@link Notification} and calls {@link #sendRaw(String)}.
     * IOExceptions are swallowed and logged at WARN level.
     */
    @Override
    public void sendNotification(Notification notification) {
        if (closed) {
            logger.warn("sendNotification called on closed transport, dropping: {}", notification.method());
            return;
        }

        String json;
        try {
            json = McpJsonMapper.instance().writeValueAsString(notification);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize notification {}: {}", notification.method(), e.getMessage());
            return;
        }

        try {
            sendRaw(json);
        } catch (IOException e) {
            logger.warn("Failed to send notification {}: {}", notification.method(), e.getMessage());
        }
    }

    /**
     * Registers a handler that receives every incoming {@link JsonRpcMessage} that is
     * NOT a direct response to a pending request (i.e. server-initiated notifications
     * and server-side requests).
     */
    @Override
    public void onIncoming(Consumer<JsonRpcMessage> handler) {
        incomingHandlers.add(handler);
    }

    /**
     * Marks the transport as closed and completes all pending request futures with
     * {@link TransportClosedException}. Subclasses should override to also tear down
     * their I/O resources, calling {@code super.close()} first.
     */
    @Override
    public void close() {
        closed = true;
        TransportClosedException ex = new TransportClosedException();
        for (CompletableFuture<Response> future : pendingRequests.values()) {
            future.completeExceptionally(ex);
        }
        pendingRequests.clear();
    }

    // ------------------------------------------------------------------
    // Subclass API
    // ------------------------------------------------------------------

    /**
     * Allocates the next auto-incrementing {@link RequestId.LongId}.
     */
    protected RequestId.LongId nextRequestId() {
        return new RequestId.LongId(requestIdCounter.incrementAndGet());
    }

    /**
     * Dispatches an incoming message:
     * <ul>
     *   <li>If it is a {@link Response}, the matching pending future is completed.</li>
     *   <li>If it is a {@link Request} or {@link Notification}, all registered
     *       {@link #incomingHandlers} are invoked in registration order.</li>
     * </ul>
     */
    protected void dispatchIncoming(JsonRpcMessage msg) {
        if (msg instanceof Response response) {
            RequestId id = null;
            if (response instanceof Response.SuccessResponse sr) {
                id = sr.id();
            } else if (response instanceof Response.ErrorResponse er) {
                id = er.id();
            }
            if (id != null) {
                CompletableFuture<Response> future = pendingRequests.remove(id);
                if (future != null) {
                    future.complete(response);
                    return;
                }
            }
            // Response with no matching pending request — still forward to handlers
            notifyHandlers(msg);
        } else {
            // Request or Notification — forward to all registered handlers
            notifyHandlers(msg);
        }
    }

    private void notifyHandlers(JsonRpcMessage msg) {
        List<Consumer<JsonRpcMessage>> snapshot = incomingHandlers;
        for (Consumer<JsonRpcMessage> handler : snapshot) {
            try {
                handler.accept(msg);
            } catch (Exception e) {
                logger.warn("Incoming message handler threw: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Write a raw JSON string to the underlying transport channel.
     *
     * @param json the serialized JSON-RPC message
     * @throws IOException if the write fails
     */
    protected abstract void sendRaw(String json) throws IOException;

    /**
     * Start the read loop that consumes incoming messages from the underlying transport
     * and calls {@link #dispatchIncoming(JsonRpcMessage)} for each one.
     */
    protected abstract void startReadLoop();
}
