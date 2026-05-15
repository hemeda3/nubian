package com.nubian.ai.runtime.mcp.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.transport.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MCP ping / heartbeat service.
 *
 * <p>Per the MCP spec (2025-11-25):
 * <ul>
 *   <li>Either party may send a {@code ping} request at any time.</li>
 *   <li>The receiver MUST respond promptly with an empty result {@code {}}.</li>
 *   <li>No response → sender MAY consider the connection stale and terminate.</li>
 *   <li>Ping frequency SHOULD be configurable; excessive pinging SHOULD be avoided.</li>
 * </ul>
 *
 * <p>No Spring annotations. Thread-safe.
 */
public class PingService {

    private static final Logger log = LoggerFactory.getLogger(PingService.class);

    private static final String METHOD_PING = "ping";

    /** Default number of consecutive failures before invoking the failure callback. */
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;

    private final ObjectMapper objectMapper;
    private final int failureThreshold;
    private final AtomicLong idSequence = new AtomicLong(0);

    /** A record describing a ping failure event passed to the {@code onFailure} callback. */
    public record PingFailure(int consecutiveFailures, Throwable lastError) {}

    /** Constructs a PingService with default settings. */
    public PingService() {
        this(McpJsonMapper.instance(), DEFAULT_FAILURE_THRESHOLD);
    }

    /** Constructs a PingService with configurable failure threshold. */
    public PingService(int failureThreshold) {
        this(McpJsonMapper.instance(), failureThreshold);
    }

    /** Constructs a PingService with a custom mapper and failure threshold. */
    public PingService(ObjectMapper objectMapper, int failureThreshold) {
        this.objectMapper = objectMapper;
        this.failureThreshold = failureThreshold;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends a single {@code ping} request over the transport, measures the round-trip
     * time, and returns a {@link CompletableFuture} that resolves to the {@link Duration}.
     *
     * <p>If the server does not respond within {@code timeout}, the future completes
     * exceptionally with a {@link TimeoutException}.
     *
     * @param transport the MCP transport to use
     * @param timeout   maximum time to wait for a pong response
     * @return future resolving to the measured round-trip duration
     */
    public CompletableFuture<Duration> ping(McpTransport transport, Duration timeout) {
        RequestId id = RequestId.of("ping-" + idSequence.incrementAndGet());
        ObjectNode emptyParams = objectMapper.createObjectNode();
        Request pingRequest = new Request(id, METHOD_PING, emptyParams);

        Instant start = Instant.now();
        return transport.send(pingRequest)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(response -> Duration.between(start, Instant.now()))
                .exceptionally(ex -> {
                    if (ex instanceof TimeoutException
                            || ex.getCause() instanceof TimeoutException) {
                        CompletableFuture<Duration> failed = new CompletableFuture<>();
                        failed.completeExceptionally(
                                new TimeoutException("Ping timed out after " + timeout));
                        // rethrow wrapped so thenApply chain fails
                        throw new RuntimeException(
                                new TimeoutException("Ping timed out after " + timeout));
                    }
                    throw new RuntimeException("Ping failed: " + ex.getMessage(), ex);
                });
    }

    /**
     * Starts a periodic heartbeat that pings the server at the given cadence.
     *
     * <p>On each tick a ping is sent with a timeout equal to the cadence. Consecutive
     * failures are counted; when the count reaches the configured {@link #failureThreshold}
     * the {@code onFailure} callback is invoked (once per threshold crossing, not per
     * subsequent failure). The count resets on any successful ping.
     *
     * @param transport the MCP transport to use
     * @param cadence   interval between pings (also used as per-ping timeout)
     * @param onFailure callback invoked when consecutive failures reach the threshold
     * @return an {@link AutoCloseable} that stops the heartbeat when closed
     */
    public AutoCloseable startHeartbeat(
            McpTransport transport,
            Duration cadence,
            Consumer<PingFailure> onFailure) {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-ping-heartbeat");
            t.setDaemon(true);
            return t;
        });

        AtomicInteger consecutiveFailures = new AtomicInteger(0);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                ping(transport, cadence).whenComplete((duration, ex) -> {
                    if (ex != null) {
                        int failures = consecutiveFailures.incrementAndGet();
                        log.warn("Ping failed (consecutive={}): {}", failures, ex.getMessage());
                        if (failures == failureThreshold) {
                            try {
                                onFailure.accept(new PingFailure(failures, ex));
                            } catch (Exception callbackEx) {
                                log.warn("onFailure callback threw: {}", callbackEx.getMessage(), callbackEx);
                            }
                        }
                    } else {
                        if (consecutiveFailures.getAndSet(0) > 0) {
                            log.info("Ping recovered after {} consecutive failures; rtt={}",
                                    consecutiveFailures.get(), duration);
                        } else {
                            log.debug("Ping ok; rtt={}", duration);
                        }
                    }
                });
            } catch (Exception ex) {
                log.warn("Unexpected error scheduling ping: {}", ex.getMessage(), ex);
            }
        }, cadence.toMillis(), cadence.toMillis(), TimeUnit.MILLISECONDS);

        return () -> {
            future.cancel(false);
            scheduler.shutdown();
        };
    }

    /**
     * Registers a handler on the transport that responds to incoming {@code ping}
     * requests from the server with an empty result {@code {}}.
     *
     * <p>Per spec the receiver MUST respond promptly. Responses are sent via
     * {@link McpTransport#send} as a synthetic request (the current transport interface
     * does not expose a raw write path). In practice the real transport implementation
     * will need to write the JSON-RPC response directly; this stub logs at DEBUG so
     * the integration point is visible.
     *
     * @param transport the transport to register the handler on
     */
    public void onIncomingPing(McpTransport transport) {
        transport.onIncoming(message -> {
            if (!(message instanceof Request req)) {
                return;
            }
            if (!METHOD_PING.equals(req.method())) {
                return;
            }
            // TODO (slice 3): transport.sendResponse(new Response.SuccessResponse(
            //     req.id(), objectMapper.createObjectNode()))
            // For now, log the receipt so the integration point is visible.
            log.debug("Received ping id={}; would respond with empty result {{}} "
                    + "— wire transport.sendResponse() when McpTransport exposes it",
                    req.id());
        });
    }
}
