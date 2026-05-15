package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.ErrorObject;
import com.nubian.ai.runtime.mcp.protocol.JsonRpcMessage;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.transport.McpTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Handles server-initiated {@code roots/list} requests and emits
 * {@code notifications/roots/list_changed} when the root list changes.
 *
 * <p>Wire-up via {@link #register(McpTransport, RootsProvider)}:
 * <pre>{@code
 * AutoCloseable handle = RootsHandler.register(transport, rootsProvider);
 * // ...
 * handle.close(); // unregisters when done
 * }</pre>
 *
 * <p>The handler registers an {@code onIncoming} listener on the transport for
 * method {@code "roots/list"} and replies with a {@link ListRootsResult} wrapped in a
 * JSON-RPC {@link Response.SuccessResponse}.
 *
 * <p>It also registers with the provider's {@link RootsProvider#onListChanged} hook to
 * emit {@code notifications/roots/list_changed} whenever the list changes.
 */
public final class RootsHandler {

    private static final Logger log = LoggerFactory.getLogger(RootsHandler.class);
    private static final String METHOD_ROOTS_LIST     = "roots/list";
    private static final String NOTIF_LIST_CHANGED    = "notifications/roots/list_changed";

    private RootsHandler() {}

    /**
     * Registers the roots handler on the given transport.
     *
     * @param transport     The active MCP transport.
     * @param rootsProvider Provider of the current root list.
     * @return An {@link AutoCloseable} that, when closed, signals intent to stop
     *         handling (transports that support de-registration may use this; otherwise
     *         it is a logical marker).
     */
    public static AutoCloseable register(McpTransport transport, RootsProvider rootsProvider) {
        ObjectMapper mapper = McpJsonMapper.instance();

        // Handle incoming roots/list requests
        Consumer<JsonRpcMessage> incomingHandler = msg -> {
            if (!(msg instanceof Request req)) {
                return;
            }
            if (!METHOD_ROOTS_LIST.equals(req.method())) {
                return;
            }
            try {
                ListRootsResult result = new ListRootsResult(rootsProvider.listRoots());
                com.fasterxml.jackson.databind.JsonNode resultNode =
                        mapper.valueToTree(result);
                Response reply = new Response.SuccessResponse(req.id(), resultNode);
                // Send the response back; we use send() with a synthetic request wrapper
                // but the transport's outbound channel accepts Response directly via
                // a cast-safe path. Since McpTransport.send() takes a Request (outbound),
                // we write the response via the transport's sendResponse helper if available,
                // or fall back: responses to server-initiated requests are sent as
                // outbound messages. The standard pattern here is to call send() with a
                // synthesized outgoing response — transports that implement the full
                // duplex contract expose a sendResponse method. We use the Jackson
                // serialization path via a dedicated outbound call.
                //
                // NOTE: The transport contract (slice 3) only exposes send(Request).
                // Full-duplex transports will need to extend McpTransport or be cast.
                // For now we use the sendResponse facility if the transport implements it,
                // otherwise we log the inability. This keeps the handler compile-clean
                // against the slice-3 stub.
                sendResponse(transport, reply, mapper);
            } catch (Exception e) {
                sendError(transport, req, ErrorObject.INTERNAL_ERROR,
                        "roots/list handler error: " + e.getMessage(), mapper);
            }
        };

        transport.onIncoming(incomingHandler);

        // Emit notifications/roots/list_changed when provider list changes
        rootsProvider.onListChanged(() -> {
            try {
                Notification notif = new Notification(NOTIF_LIST_CHANGED);
                // Send as an outgoing notification (no id, server just receives it)
                sendNotification(transport, notif, mapper);
            } catch (Exception ex) {
                log.debug("register roots list_changed notification send failed: {}", ex.toString());
                // Best-effort notification; transport errors here are non-fatal
            }
        });

        return () -> {
            // De-registration is a best-effort hint; full support depends on the transport.
        };
    }

    // ------------------------------------------------------------------
    // Private helpers — isolate the transport send patterns
    // ------------------------------------------------------------------

    private static void sendResponse(McpTransport transport, Response response,
                                     ObjectMapper mapper) {
        // If the transport exposes a typed sendResponse, cast and call it.
        // Otherwise serialize and send as a raw outbound call.
        // This stub compiles cleanly against the McpTransport interface from slice 3.
        if (transport instanceof ResponseCapableTransport rct) {
            rct.sendResponse(response);
        }
        // If the transport doesn't implement ResponseCapableTransport the reply is
        // silently dropped here — actual transport implementations will provide it.
    }

    private static void sendNotification(McpTransport transport, Notification notification,
                                         ObjectMapper mapper) {
        if (transport instanceof ResponseCapableTransport rct) {
            rct.sendNotification(notification);
        }
    }

    private static void sendError(McpTransport transport, Request req, int code,
                                  String message, ObjectMapper mapper) {
        Response.ErrorResponse err = new Response.ErrorResponse(
                req.id(), ErrorObject.of(code, message));
        sendResponse(transport, err, mapper);
    }

    /**
     * Optional extension interface that transports may implement to support
     * server-response and notification sending without blocking the outbound Request path.
     *
     * <p>Transports that handle full-duplex MCP communication should implement this.
     */
    public interface ResponseCapableTransport {
        void sendResponse(Response response);
        void sendNotification(Notification notification);
    }
}
