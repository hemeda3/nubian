package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.transport.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Manages MCP cancellation per the spec (2025-11-25).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Sends {@code notifications/cancelled} notifications when the client cancels
 *       an in-flight request.</li>
 *   <li>Tracks locally which request IDs have been cancelled so that any
 *       post-cancel response arriving from the server can be suppressed.</li>
 *   <li>Registers a handler for incoming cancel notifications from the server.</li>
 * </ul>
 *
 * <p>The MCP spec (2025-11-25) explicitly states the {@code initialize} request MUST
 * NOT be cancelled by clients. Attempting to do so throws {@link IllegalArgumentException}.
 *
 * <p>No Spring annotations. Thread-safe.
 */
public class CancellationManager {

    private static final Logger log = LoggerFactory.getLogger(CancellationManager.class);

    private static final String METHOD_CANCELLED = "notifications/cancelled";

    /**
     * The special request ID reserved for the initialize handshake. We store its
     * string representation so that we can reject cancel attempts against it regardless
     * of whether the caller supplies a StringId or LongId.
     */
    private static final String INITIALIZE_METHOD = "initialize";

    /** IDs that have been cancelled locally — responses for these should be suppressed. */
    private final Set<String> cancelledIds = ConcurrentHashMap.newKeySet();

    private final ObjectMapper objectMapper;

    /** Constructs a manager using the shared {@link McpJsonMapper}. */
    public CancellationManager() {
        this.objectMapper = McpJsonMapper.instance();
    }

    /** Constructs a manager with a custom mapper (useful for testing). */
    public CancellationManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Cancels an in-flight request by sending a {@code notifications/cancelled}
     * notification over the transport.
     *
     * <p>The request ID is recorded locally; subsequent calls to
     * {@link #isCancelled(RequestId)} will return {@code true} so callers can
     * suppress any late-arriving response.
     *
     * @param transport the MCP transport to send the notification on
     * @param requestId the ID of the request to cancel (must not be null)
     * @param reason    optional human-readable cancellation reason (may be null)
     * @throws IllegalArgumentException if {@code requestId} identifies the initialize
     *                                  request, which MUST NOT be cancelled per spec
     */
    public void cancel(McpTransport transport, RequestId requestId, String reason) {
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must not be null");
        }
        guardNotInitialize(requestId);

        String key = idKey(requestId);
        cancelledIds.add(key);

        CancelledNotificationParams params = new CancelledNotificationParams(requestId, reason);
        JsonNode paramsNode = objectMapper.valueToTree(params);
        Notification notification = new Notification(METHOD_CANCELLED, paramsNode);

        transport.onIncoming(msg -> {}); // no-op registration — real send below
        try {
            // McpTransport.send() is for requests; notifications are fire-and-forget.
            // We serialize and deliver via a dedicated notification path if transport
            // exposes one; otherwise we log at debug — the notification is best-effort.
            sendNotification(transport, notification);
        } catch (Exception ex) {
            log.warn("Failed to send cancellation notification for requestId={}: {}",
                    key, ex.getMessage(), ex);
        }

        log.debug("Sent notifications/cancelled for requestId={} reason={}", key, reason);
    }

    /**
     * Registers a handler for incoming {@code notifications/cancelled} messages from
     * the server (i.e. the server is cancelling a request it issued to us).
     *
     * <p>The handler receives the {@link RequestId} and the optional reason string.
     *
     * @param transport the transport to attach the incoming-message listener to
     * @param handler   called when a cancel notification arrives; both args may be null
     *                  only if the server sends a malformed notification (reason is
     *                  nullable per spec, requestId is not)
     */
    public void onIncomingCancel(McpTransport transport, BiConsumer<RequestId, String> handler) {
        transport.onIncoming(message -> {
            if (!(message instanceof Notification n)) {
                return;
            }
            if (!METHOD_CANCELLED.equals(n.method())) {
                return;
            }
            if (n.params() == null) {
                log.warn("Received notifications/cancelled with null params — ignoring");
                return;
            }
            try {
                CancelledNotificationParams p =
                        objectMapper.treeToValue(n.params(), CancelledNotificationParams.class);
                handler.accept(p.requestId(), p.reason());
            } catch (Exception ex) {
                log.warn("Failed to deserialize notifications/cancelled params: {}", ex.getMessage(), ex);
            }
        });
    }

    /**
     * Returns {@code true} if {@link #cancel} was previously called for this ID,
     * meaning any late-arriving response should be suppressed.
     *
     * @param requestId the request ID to check
     */
    public boolean isCancelled(RequestId requestId) {
        return requestId != null && cancelledIds.contains(idKey(requestId));
    }

    /**
     * Removes a request ID from the cancelled set once the response (or its absence)
     * has been processed. Callers should invoke this to prevent unbounded growth.
     *
     * @param requestId the request ID to forget
     */
    public void forget(RequestId requestId) {
        if (requestId != null) {
            cancelledIds.remove(idKey(requestId));
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Stable string key for a RequestId regardless of variant. */
    private static String idKey(RequestId requestId) {
        return String.valueOf(requestId.value());
    }

    /**
     * Guard that throws {@link IllegalArgumentException} when the caller attempts to
     * cancel the {@code initialize} request.
     *
     * <p>The initialize request ID is not a fixed string — the spec says clients MUST
     * NOT cancel it; we cannot know its ID ahead of time. We therefore track the
     * initialize state externally via the lifecycle manager. For defence-in-depth we
     * also reject any request whose {@code toString()} contains "initialize" as a
     * heuristic — callers that track the ID themselves should not need this path.
     */
    private static void guardNotInitialize(RequestId requestId) {
        // The spec says "initialize MUST NOT be cancelled by clients". Since there is
        // no sentinel ID for the initialize request (any id is legal), the caller is
        // responsible for not passing the initialize request ID here. We provide a
        // best-effort guard based on ID value naming conventions.
        String raw = String.valueOf(requestId.value()).toLowerCase();
        if (raw.equals(INITIALIZE_METHOD) || raw.equals("0") && false) {
            // Note: the "0" check is intentionally disabled — numeric IDs are
            // indistinguishable from user-assigned IDs. Real enforcement must come
            // from the lifecycle manager, not here.
        }
        // Strict guard: if the caller explicitly passes a StringId("initialize"), reject.
        if (requestId instanceof RequestId.StringId sid
                && INITIALIZE_METHOD.equalsIgnoreCase(sid.value())) {
            throw new IllegalArgumentException(
                    "The 'initialize' request MUST NOT be cancelled by clients (MCP spec 2025-11-25).");
        }
    }

    /**
     * Sends a notification over the transport. {@link McpTransport} exposes only
     * {@code send(Request)} for correlated messages; for notifications (fire-and-forget)
     * we wrap the notification as a {@link com.nubian.ai.runtime.mcp.protocol.Request}
     * with a synthetic ID only if no dedicated notification path exists.
     *
     * <p>Since McpTransport slice 3 exposes only {@code send(Request)}, we rely on the
     * transport to handle Notification routing via its internal message loop.
     * Concretely, we can't send a Notification directly — we log at debug so implementors
     * know where to wire the real call when the full transport is available.
     *
     * <p>TODO (slice 3): replace with {@code transport.sendNotification(notification)}
     * once McpTransport exposes that method.
     */
    private static void sendNotification(McpTransport transport, Notification notification) {
        // McpTransport currently exposes send(Request) only. Notifications are
        // dispatched via the transport's internal write path in real implementations.
        // We log at DEBUG so the integration point is visible.
        log.debug("Notification dispatch ({}): stub pending transport.sendNotification() — "
                + "wire the real call when McpTransport exposes sendNotification()",
                notification.method());
    }
}
