package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.ProgressToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks progress notifications for in-flight MCP requests.
 *
 * <p>Per the MCP spec (2025-11-25):
 * <ul>
 *   <li>Requestors opt in via {@code _meta.progressToken} in the request params.</li>
 *   <li>{@code progress} MUST increase monotonically, even when {@code total} is unknown.</li>
 *   <li>Both sides SHOULD rate-limit; this implementation drops notifications when
 *       more than 100 per second arrive for the same token.</li>
 *   <li>Notifications MUST stop after the operation completes. Callers must call
 *       {@link #removeListener(ProgressToken, ProgressListener)} (or
 *       {@link #removeAllListeners(ProgressToken)}) on completion.</li>
 * </ul>
 *
 * <p>No Spring annotations. Thread-safe.
 */
public class ProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(ProgressTracker.class);

    /** Maximum notifications accepted per token per second. */
    private static final int RATE_LIMIT_PER_SECOND = 100;

    /** Listener interface invoked on each progress notification. */
    public interface ProgressListener {
        /**
         * Called when a progress notification arrives for the token this listener was
         * registered against.
         *
         * @param current current progress value (monotonically increasing per spec)
         * @param total   upper bound, or {@code null} if not known
         * @param message human-readable status string, or {@code null}
         */
        void onProgress(double current, Double total, String message);
    }

    // -------------------------------------------------------------------------
    // Per-token state
    // -------------------------------------------------------------------------

    private record TokenState(
            List<ProgressListener> listeners,
            AtomicLong lastWindowStart,
            AtomicLong windowCount,
            AtomicLong lastProgress) {

        static TokenState create() {
            return new TokenState(
                    new CopyOnWriteArrayList<>(),
                    new AtomicLong(System.currentTimeMillis()),
                    new AtomicLong(0),
                    new AtomicLong(Double.doubleToLongBits(Double.NEGATIVE_INFINITY)));
        }
    }

    private final Map<String, TokenState> tokenStates = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    /** Constructs a ProgressTracker using the shared mapper. */
    public ProgressTracker() {
        this.objectMapper = McpJsonMapper.instance();
    }

    /** Constructs a ProgressTracker with a custom mapper. */
    public ProgressTracker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Allocates a new unique {@link ProgressToken} backed by a UUID-derived string.
     *
     * <p>The token is unique across all active requests within this JVM process.
     *
     * @return a fresh, unique progress token
     */
    public ProgressToken allocate() {
        String id = UUID.randomUUID().toString();
        tokenStates.computeIfAbsent(id, k -> TokenState.create());
        return ProgressToken.of(id);
    }

    /**
     * Registers a {@link ProgressListener} for the given token.
     *
     * <p>Multiple listeners may be registered for the same token; all are called in
     * registration order on each {@link #receive} call.
     *
     * @param token    the progress token to observe
     * @param listener the listener to invoke on progress events
     */
    public void onProgress(ProgressToken token, ProgressListener listener) {
        if (token == null) throw new IllegalArgumentException("token must not be null");
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        stateFor(token).listeners().add(listener);
    }

    /**
     * Removes a specific listener for the given token.
     *
     * @param token    the progress token
     * @param listener the listener to remove
     */
    public void removeListener(ProgressToken token, ProgressListener listener) {
        if (token == null || listener == null) return;
        TokenState state = tokenStates.get(tokenKey(token));
        if (state != null) {
            state.listeners().remove(listener);
        }
    }

    /**
     * Removes all listeners for the given token and releases its state.
     *
     * <p>Callers MUST invoke this (or {@link #removeListener}) when the associated
     * operation completes to avoid memory leaks.
     *
     * @param token the progress token to deregister
     */
    public void removeAllListeners(ProgressToken token) {
        if (token != null) {
            tokenStates.remove(tokenKey(token));
        }
    }

    /**
     * Dispatches an incoming {@link ProgressNotificationParams} to registered listeners.
     *
     * <p>Enforces:
     * <ol>
     *   <li>Rate limit: drops the notification (with a DEBUG log) if more than
     *       {@value #RATE_LIMIT_PER_SECOND} notifications have arrived for this token
     *       in the current 1-second window.</li>
     *   <li>Monotonic progress: logs a WARNING if {@code progress} is not greater than
     *       the last seen value per spec requirement.</li>
     * </ol>
     *
     * @param params the incoming progress notification params
     */
    public void receive(ProgressNotificationParams params) {
        if (params == null) return;
        String key = tokenKey(params.progressToken());
        TokenState state = tokenStates.get(key);
        if (state == null) {
            log.debug("No listeners for progressToken={}; dropping notification", key);
            return;
        }

        // Rate limit — sliding 1-second window per token
        if (isRateLimited(state, key)) {
            return;
        }

        // Monotonic check
        double incoming = params.progress();
        double last = Double.longBitsToDouble(state.lastProgress().get());
        if (incoming < last) {
            log.warn("Non-monotonic progress for token={}: received {} but last was {} — spec requires "
                    + "progress to increase monotonically", key, incoming, last);
            // Per spec we still dispatch; spec says MUST be monotonic but doesn't say receivers
            // must reject. We warn and continue.
        }
        state.lastProgress().set(Double.doubleToLongBits(Math.max(incoming, last)));

        for (ProgressListener listener : state.listeners()) {
            try {
                listener.onProgress(params.progress(), params.total(), params.message());
            } catch (Exception ex) {
                log.warn("ProgressListener threw for token={}: {}", key, ex.getMessage(), ex);
            }
        }
    }

    /**
     * Static helper that attaches {@code _meta.progressToken} to a Jackson
     * {@link JsonNode} params object (must be an {@link ObjectNode}).
     *
     * @param params the request params to mutate
     * @param token  the token to inject
     * @return the same node, mutated in-place
     */
    public static JsonNode attachToRequest(JsonNode params, ProgressToken token) {
        if (!(params instanceof ObjectNode on)) {
            throw new IllegalArgumentException("params must be an ObjectNode");
        }
        return ProgressOptIn.withProgressToken(on, token);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private TokenState stateFor(ProgressToken token) {
        return tokenStates.computeIfAbsent(tokenKey(token), k -> TokenState.create());
    }

    private static String tokenKey(ProgressToken token) {
        return String.valueOf(token.value());
    }

    /**
     * Returns {@code true} and logs a DEBUG message if this notification should be
     * dropped due to rate limiting. Resets the window counter every second.
     */
    private static boolean isRateLimited(TokenState state, String key) {
        long now = System.currentTimeMillis();
        long windowStart = state.lastWindowStart().get();
        if (now - windowStart >= 1_000L) {
            // New window: reset counter
            state.lastWindowStart().set(now);
            state.windowCount().set(1);
            return false;
        }
        long count = state.windowCount().incrementAndGet();
        if (count > RATE_LIMIT_PER_SECOND) {
            log.debug("Rate-limited progress notification for token={} (count={} in current window)",
                    key, count);
            return true;
        }
        return false;
    }
}
