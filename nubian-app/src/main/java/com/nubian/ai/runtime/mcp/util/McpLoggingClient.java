package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.transport.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client-side MCP logging capability.
 *
 * <p>Per the MCP spec (2025-11-25) logging page:
 * <ul>
 *   <li>Clients send {@code logging/setLevel} to control the minimum log level that
 *       the server should emit.</li>
 *   <li>Servers push {@code notifications/message} notifications carrying
 *       {@link LoggingMessageNotificationParams}.</li>
 * </ul>
 *
 * <p>No Spring annotations. Thread-safe.
 */
public class McpLoggingClient {

    private static final Logger log = LoggerFactory.getLogger(McpLoggingClient.class);

    private static final String METHOD_SET_LEVEL = "logging/setLevel";
    private static final String NOTIFICATION_MESSAGE = "notifications/message";

    private final ObjectMapper objectMapper;
    private final AtomicLong idSequence = new AtomicLong(0);

    /** Registered log-message handlers; guarded by CopyOnWriteArrayList. */
    private final List<HandlerEntry> handlers = new CopyOnWriteArrayList<>();

    private record HandlerEntry(Consumer<LoggingMessageNotificationParams> handler) {}

    /** Constructs a client using the shared {@link McpJsonMapper}. */
    public McpLoggingClient() {
        this.objectMapper = McpJsonMapper.instance();
    }

    /** Constructs a client with a custom mapper. */
    public McpLoggingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends a {@code logging/setLevel} request to the server, asking it to suppress
     * log messages below the given {@link LogLevel}.
     *
     * <p>The returned future resolves to {@code null} on success, or completes
     * exceptionally if the server returns an error or the transport fails.
     *
     * @param transport the MCP transport to use
     * @param level     the minimum log level the client wishes to receive
     * @return a future that resolves to {@code null} on success
     */
    public CompletableFuture<Void> setLevel(McpTransport transport, LogLevel level) {
        if (level == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("level must not be null"));
        }

        RequestId id = RequestId.of("setlevel-" + idSequence.incrementAndGet());
        SetLevelParams params = new SetLevelParams(level);
        JsonNode paramsNode = objectMapper.valueToTree(params);
        Request request = new Request(id, METHOD_SET_LEVEL, paramsNode);

        return transport.send(request).thenApply(response -> {
            if (response instanceof com.nubian.ai.runtime.mcp.protocol.Response.ErrorResponse err) {
                throw new RuntimeException(
                        "logging/setLevel failed: " + err.error().message()
                                + " (code=" + err.error().code() + ")");
            }
            log.debug("logging/setLevel acknowledged; level={}", level);
            return (Void) null;
        });
    }

    /**
     * Registers a handler for {@code notifications/message} notifications from the
     * server. The handler is invoked for every log message the server sends.
     *
     * <p>Multiple handlers may be registered; all are called in registration order.
     * The returned {@link AutoCloseable} unregisters the handler when closed.
     *
     * @param transport the transport to listen on (must already have been connected;
     *                  the handler is registered on the transport's incoming-message
     *                  dispatcher)
     * @param handler   called for each incoming log notification
     * @return an {@link AutoCloseable} that unregisters this handler when closed
     */
    public AutoCloseable onLogMessage(
            McpTransport transport,
            Consumer<LoggingMessageNotificationParams> handler) {

        HandlerEntry entry = new HandlerEntry(handler);
        handlers.add(entry);

        // Register an incoming-message listener on the transport the first time —
        // subsequent registrations piggyback on the CopyOnWriteArrayList dispatch.
        // (Multiple transport.onIncoming registrations are additive per McpTransport
        // contract, so registering once per handler is also valid.)
        transport.onIncoming(message -> {
            if (!(message instanceof Notification n)) {
                return;
            }
            if (!NOTIFICATION_MESSAGE.equals(n.method())) {
                return;
            }
            if (n.params() == null) {
                log.warn("Received notifications/message with null params — ignoring");
                return;
            }
            try {
                LoggingMessageNotificationParams p =
                        objectMapper.treeToValue(n.params(), LoggingMessageNotificationParams.class);
                // Dispatch to all currently registered handlers
                for (HandlerEntry e : handlers) {
                    try {
                        e.handler().accept(p);
                    } catch (Exception callbackEx) {
                        log.warn("Log message handler threw: {}", callbackEx.getMessage(), callbackEx);
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to deserialize notifications/message params: {}", ex.getMessage(), ex);
            }
        });

        return () -> handlers.remove(entry);
    }
}
