package com.nubian.ai.runtime.mcp.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.ErrorObject;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.transport.McpTransport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the MCP session lifecycle: initialize → initialized notification → shutdown.
 *
 * <p>This is a pure POJO — no Spring annotations. Spring wiring is handled by a
 * separate orchestrator bean that constructs and owns instances of this class.
 *
 * <p>Thread-safety: state transitions are guarded by an {@link AtomicReference};
 * concurrent calls to {@link #initialize} after the first will fail fast with
 * {@link IllegalStateException}.
 */
public class LifecycleManager {

    /**
     * Observable lifecycle states for this MCP session.
     */
    public enum State {
        NOT_INITIALIZED,
        INITIALIZING,
        INITIALIZED,
        SHUTTING_DOWN,
        SHUT_DOWN
    }

    private static final String METHOD_INITIALIZE = "initialize";
    private static final String NOTIFICATION_INITIALIZED = "notifications/initialized";

    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_INITIALIZED);
    private final AtomicLong idCounter = new AtomicLong(1);
    private final ObjectMapper objectMapper;

    /**
     * Constructs a LifecycleManager using the shared {@link McpJsonMapper}.
     */
    public LifecycleManager() {
        this.objectMapper = McpJsonMapper.instance();
    }

    /**
     * Constructs a LifecycleManager with a custom ObjectMapper (useful for testing).
     *
     * @param objectMapper the mapper to use for serializing/deserializing MCP messages
     */
    public LifecycleManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the current lifecycle state.
     */
    public State getState() {
        return state.get();
    }

    /**
     * Performs the MCP initialization handshake:
     * <ol>
     *   <li>Sends an {@code initialize} JSON-RPC request over {@code transport}.</li>
     *   <li>Waits for the server's response and deserializes it as {@link InitializeResult}.</li>
     *   <li>Validates the returned {@code protocolVersion} is in
     *       {@link ProtocolVersion#SUPPORTED_SET}; throws {@link IllegalStateException}
     *       (i.e. "client SHOULD disconnect") if not.</li>
     *   <li>Sends {@code notifications/initialized} immediately after a valid response.</li>
     *   <li>Transitions state to {@link State#INITIALIZED}.</li>
     * </ol>
     *
     * <p>Only one successful call is allowed per instance. A second call while
     * {@link #getState()} is not {@link State#NOT_INITIALIZED} throws
     * {@link IllegalStateException}.
     *
     * @param transport  the transport to use for this session
     * @param clientCaps the capabilities this client advertises
     * @param clientInfo metadata about this client
     * @return a future that resolves to the server's {@link InitializeResult}
     */
    public CompletableFuture<InitializeResult> initialize(
            McpTransport transport,
            ClientCapabilities clientCaps,
            ClientInfo clientInfo) {

        if (!state.compareAndSet(State.NOT_INITIALIZED, State.INITIALIZING)) {
            State current = state.get();
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Cannot initialize: current state is " + current));
        }

        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                clientCaps,
                clientInfo);

        JsonNode paramsNode;
        try {
            paramsNode = objectMapper.valueToTree(params);
        } catch (IllegalArgumentException ex) {
            state.set(State.NOT_INITIALIZED);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Failed to serialize initialize params", ex));
        }

        RequestId requestId = new RequestId.LongId(idCounter.getAndIncrement());
        Request initRequest = new Request(requestId, METHOD_INITIALIZE, paramsNode);

        return transport.sendRequest(initRequest)
                .thenApply(response -> {
                    validateNoError(response);
                    InitializeResult result = deserializeResult(response);
                    validateProtocolVersion(result.protocolVersion());
                    sendInitializedNotification(transport);
                    state.set(State.INITIALIZED);
                    return result;
                })
                .exceptionally(ex -> {
                    // Roll back to NOT_INITIALIZED so callers may retry on transient errors.
                    state.compareAndSet(State.INITIALIZING, State.NOT_INITIALIZED);
                    if (ex instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException("MCP initialize failed: " + ex.getMessage(), ex);
                });
    }

    /**
     * Shuts down the session by closing the transport.
     *
     * <p>Per spec, shutdown is transport-level (stdio: close stdin; HTTP: close
     * connection). This method transitions state to {@link State#SHUT_DOWN} and
     * delegates to {@link McpTransport#close()}.
     *
     * @param transport the transport to close
     */
    public void shutdown(McpTransport transport) {
        State prev = state.getAndSet(State.SHUTTING_DOWN);
        if (prev == State.SHUT_DOWN) {
            return;
        }
        try {
            transport.close();
        } finally {
            state.set(State.SHUT_DOWN);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void validateNoError(Response response) {
        if (response instanceof Response.ErrorResponse err) {
            ErrorObject error = err.error();
            throw new IllegalStateException(
                    "MCP initialize error " + error.code() + ": " + error.message());
        }
    }

    private InitializeResult deserializeResult(Response response) {
        if (!(response instanceof Response.SuccessResponse success)) {
            throw new IllegalStateException("Expected a success response but got: " + response.getClass().getSimpleName());
        }
        try {
            return objectMapper.convertValue(success.result(), InitializeResult.class);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Failed to deserialize InitializeResult: " + ex.getMessage(), ex);
        }
    }

    private void validateProtocolVersion(String serverVersion) {
        if (!ProtocolVersion.isSupported(serverVersion)) {
            throw new IllegalStateException(
                    "Server returned unsupported protocolVersion '" + serverVersion
                    + "'. Supported: " + ProtocolVersion.SUPPORTED
                    + ". Client SHOULD disconnect per MCP spec.");
        }
    }

    private void sendInitializedNotification(McpTransport transport) {
        Notification notification = new Notification(NOTIFICATION_INITIALIZED, null);
        transport.sendNotification(notification);
    }
}
