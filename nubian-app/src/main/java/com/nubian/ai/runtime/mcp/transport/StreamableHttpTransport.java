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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * MCP transport over Streamable HTTP (MCP spec 2025-11-25).
 *
 * <p>Each outbound {@link Request} is POSTed to the MCP endpoint. The server may reply
 * either with a single {@code application/json} body (containing one JSON-RPC message)
 * or with a {@code text/event-stream} body (an SSE stream that can carry multiple
 * messages, including server-initiated requests and notifications).
 *
 * <p>An optional long-lived GET SSE stream can be opened via {@link #openServerStream()}
 * for receiving server-push messages without an in-flight request.
 *
 * <p>Session management: once the server sets {@code MCP-Session-Id} (typically in the
 * initialize response), subsequent requests include that header. A 404 with an active
 * session throws {@link SessionExpiredException}.
 */
public class StreamableHttpTransport extends AbstractMcpTransport {

    private static final Logger logger = LoggerFactory.getLogger(StreamableHttpTransport.class);

    /** RFC 9110 visible-ASCII printable characters (0x21–0x7E). */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[\\x21-\\x7E]+$");

    private final URI mcpEndpoint;
    private final Supplier<Optional<String>> bearerTokenSupplier;
    private final TransportConfig config;
    private final HttpClient httpClient;

    /** Assigned by the server on the initialize response; null until then. */
    private volatile String sessionId;

    /** MCP protocol version negotiated during initialize. */
    private volatile String protocolVersion;

    /** Flag to signal the long-lived GET SSE thread to stop. */
    private volatile boolean stopServerStream = false;

    /**
     * Creates a transport that posts to {@code mcpEndpoint}.
     *
     * @param mcpEndpoint          the MCP server URL (e.g. {@code https://host/mcp})
     * @param bearerTokenSupplier  returns the current {@code Authorization} header value
     *                             ({@code "Bearer <token>"}) when a token is available
     * @param config               transport configuration (timeouts, reconnect policy)
     */
    public StreamableHttpTransport(
            URI mcpEndpoint,
            Supplier<Optional<String>> bearerTokenSupplier,
            TransportConfig config) {
        this.mcpEndpoint = mcpEndpoint;
        this.bearerTokenSupplier = bearerTokenSupplier != null ? bearerTokenSupplier : Optional::empty;
        this.config = config != null ? config : TransportConfig.DEFAULT;
        // HTTP/1.1 forced — see LlmClient for reasoning (TLS state corruption on HTTP/2 pooled conns)
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(this.config.connectTimeout())
                .build();
    }

    /**
     * Convenience constructor with no bearer token and default config.
     */
    public StreamableHttpTransport(URI mcpEndpoint) {
        this(mcpEndpoint, Optional::empty, TransportConfig.DEFAULT);
    }

    // ------------------------------------------------------------------
    // Session / protocol version setters (called by LifecycleManager)
    // ------------------------------------------------------------------

    public void setSessionId(String sessionId) {
        if (sessionId != null && !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            logger.warn("Ignoring invalid MCP-Session-Id (non-printable ASCII): {}", sessionId);
            return;
        }
        this.sessionId = sessionId;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    // ------------------------------------------------------------------
    // AbstractMcpTransport overrides
    // ------------------------------------------------------------------

    /**
     * POST the request to the MCP endpoint and handle the response.
     *
     * <p>Overrides {@link AbstractMcpTransport#send(Request)} to use HTTP semantics
     * instead of the raw write/read-loop model.
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
            return CompletableFuture.failedFuture(e);
        }

        try {
            postAndHandle(json, id, future);
        } catch (Exception e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * POST a notification to the MCP endpoint. Expects HTTP 202; logs WARN on error.
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
            HttpRequest httpRequest = buildPostRequest(json);
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 202 && response.statusCode() / 100 != 2) {
                logger.warn("Notification {} returned HTTP {}: {}",
                        notification.method(), response.statusCode(), response.body());
            }
        } catch (IOException e) {
            logger.warn("Failed to send notification {}: {}", notification.method(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted sending notification {}", notification.method());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>No-op: HTTP is request/response; there is no persistent read loop.
     * Use {@link #openServerStream()} for server-push messages.
     */
    @Override
    protected void startReadLoop() {
        // No persistent read loop for HTTP transport
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not used directly; HTTP requests are handled inside {@link #send(Request)}.
     */
    @Override
    protected void sendRaw(String json) throws IOException {
        // Not called in normal HTTP operation — send() is overridden directly.
        // Retained to satisfy the abstract contract; throw to surface misuse.
        throw new UnsupportedOperationException(
                "sendRaw is not used by StreamableHttpTransport; use send() or sendNotification()");
    }

    @Override
    public void close() {
        stopServerStream = true;
        super.close();
    }

    // ------------------------------------------------------------------
    // Server-push GET SSE stream
    // ------------------------------------------------------------------

    /**
     * Opens a long-lived GET SSE connection to receive server-initiated messages.
     * If the server returns 405 (Method Not Allowed), the call logs INFO and returns
     * without error (the server does not support server-push streams).
     *
     * <p>Reconnects up to {@link TransportConfig#maxReconnectAttempts()} times on
     * failure, honouring {@link TransportConfig#reconnectBackoff()}.
     *
     * <p>Returns immediately; the actual stream is consumed on a daemon thread.
     */
    public void openServerStream() {
        Thread sseThread = new Thread(() -> {
            String lastEventId = null;
            int attempts = 0;

            while (!closed && !stopServerStream) {
                try {
                    HttpRequest.Builder rb = HttpRequest.newBuilder(mcpEndpoint)
                            .GET()
                            .header("Accept", "text/event-stream");
                    addAuthHeader(rb);
                    addSessionHeader(rb);
                    addProtocolVersionHeader(rb);
                    if (lastEventId != null) {
                        rb.header("Last-Event-ID", lastEventId);
                    }

                    HttpResponse<InputStream> response = httpClient.send(
                            rb.build(), HttpResponse.BodyHandlers.ofInputStream());

                    int status = response.statusCode();
                    if (status == 405) {
                        logger.info("MCP server does not support GET SSE stream (405 returned), skipping");
                        return;
                    }
                    if (status / 100 != 2) {
                        logger.warn("GET SSE stream returned HTTP {}", status);
                        attempts++;
                        if (attempts > config.maxReconnectAttempts()) {
                            logger.warn("Max reconnect attempts ({}) exceeded for GET SSE stream, giving up",
                                    config.maxReconnectAttempts());
                            return;
                        }
                        sleepBackoff(attempts);
                        continue;
                    }

                    // Reset attempt counter on successful connection
                    attempts = 0;
                    logger.debug("GET SSE stream connected");

                    // Consume the SSE stream synchronously on this thread
                    lastEventId = consumeSseStream(response.body(), lastEventId);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.debug("GET SSE stream thread interrupted");
                    return;
                } catch (IOException e) {
                    if (closed || stopServerStream) {
                        return;
                    }
                    logger.warn("GET SSE stream I/O error: {}", e.getMessage());
                    attempts++;
                    if (attempts > config.maxReconnectAttempts()) {
                        logger.warn("Max reconnect attempts ({}) exceeded for GET SSE stream, giving up",
                                config.maxReconnectAttempts());
                        return;
                    }
                    sleepBackoff(attempts);
                }
            }
        });
        sseThread.setName("mcp-http-sse-stream");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Issues the POST, reads the response content-type, and populates {@code future}.
     */
    private void postAndHandle(String json, RequestId requestId,
            CompletableFuture<Response> future) throws IOException, InterruptedException {
        HttpRequest httpRequest = buildPostRequest(json);

        // Capture session id at request time for 404 detection
        String activeSessionId = this.sessionId;

        HttpResponse<InputStream> response = httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();

        if (status == 404 && activeSessionId != null) {
            pendingRequests.remove(requestId);
            future.completeExceptionally(new SessionExpiredException(activeSessionId));
            return;
        }

        if (status == 400) {
            pendingRequests.remove(requestId);
            future.completeExceptionally(new ConnectErrorException(
                    "MCP server returned HTTP 400 for request id=" + requestId));
            return;
        }

        if (status / 100 != 2) {
            pendingRequests.remove(requestId);
            future.completeExceptionally(new ConnectErrorException(
                    "MCP server returned HTTP " + status + " for request id=" + requestId));
            return;
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");

        if (contentType.contains("text/event-stream")) {
            // SSE stream — consume on a separate daemon thread; the future will be
            // completed when we see the matching response event.
            InputStream body = response.body();
            Thread sseThread = new Thread(() -> {
                try {
                    consumeSseStreamForRequest(body, requestId, future);
                } catch (Exception e) {
                    pendingRequests.remove(requestId);
                    future.completeExceptionally(e);
                }
            });
            sseThread.setName("mcp-http-sse-req-" + requestId);
            sseThread.setDaemon(true);
            sseThread.start();

        } else {
            // application/json — single message
            try {
                byte[] bytes = response.body().readAllBytes();
                String body = new String(bytes, StandardCharsets.UTF_8);
                JsonRpcMessage msg = McpJsonMapper.instance().readValue(body, JsonRpcMessage.class);
                pendingRequests.remove(requestId);
                if (msg instanceof Response resp) {
                    future.complete(resp);
                } else {
                    dispatchIncoming(msg);
                    future.completeExceptionally(new InvalidResponseException(
                            "Server returned a non-Response message for request id=" + requestId));
                }
            } catch (JsonProcessingException e) {
                pendingRequests.remove(requestId);
                future.completeExceptionally(e);
            }
        }
    }

    /**
     * Reads an SSE stream associated with a specific outbound request.
     * Non-response messages are dispatched via {@link #dispatchIncoming}.
     * The first matching {@link Response} completes {@code future}.
     *
     * @return the last seen event-ID (for resumability)
     */
    private String consumeSseStreamForRequest(InputStream stream,
            RequestId requestId, CompletableFuture<Response> future) {
        final String[] lastEventId = {null};
        final boolean[] resolved = {false};

        SubmissionPublisher<SseEvent> publisher = new SubmissionPublisher<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription sub;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.sub = subscription;
                sub.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SseEvent event) {
                if (event.id() != null) {
                    lastEventId[0] = event.id();
                }
                if (!event.hasData()) {
                    return;
                }
                try {
                    JsonRpcMessage msg = McpJsonMapper.instance()
                            .readValue(event.data(), JsonRpcMessage.class);
                    if (msg instanceof Response resp && matchesRequest(resp, requestId)) {
                        resolved[0] = true;
                        pendingRequests.remove(requestId);
                        future.complete(resp);
                    } else {
                        dispatchIncoming(msg);
                    }
                } catch (JsonProcessingException e) {
                    logger.warn("Failed to parse SSE event data: {}", e.getMessage());
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (!resolved[0]) {
                    pendingRequests.remove(requestId);
                    future.completeExceptionally(throwable);
                }
            }

            @Override
            public void onComplete() {
                if (!resolved[0]) {
                    pendingRequests.remove(requestId);
                    future.completeExceptionally(new ConnectErrorException(
                            "SSE stream ended without a response for request id=" + requestId));
                }
            }
        });

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            SseEventParser.parseInto(reader, publisher);
        } catch (IOException e) {
            publisher.closeExceptionally(e);
        } finally {
            publisher.close();
        }

        return lastEventId[0];
    }

    /**
     * Consumes a GET SSE stream, dispatching every message via
     * {@link #dispatchIncoming}. Returns the last seen event-ID.
     */
    private String consumeSseStream(InputStream stream, String initialLastEventId) {
        final String[] lastEventId = {initialLastEventId};

        SubmissionPublisher<SseEvent> publisher = new SubmissionPublisher<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription sub;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.sub = subscription;
                sub.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SseEvent event) {
                if (event.id() != null) {
                    lastEventId[0] = event.id();
                }
                if (!event.hasData()) {
                    return;
                }
                try {
                    JsonRpcMessage msg = McpJsonMapper.instance()
                            .readValue(event.data(), JsonRpcMessage.class);
                    dispatchIncoming(msg);
                } catch (JsonProcessingException e) {
                    logger.warn("Failed to parse GET SSE event data: {}", e.getMessage());
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (!closed && !stopServerStream) {
                    logger.debug("GET SSE stream error: {}", throwable.getMessage());
                }
            }

            @Override
            public void onComplete() {
                logger.debug("GET SSE stream completed");
            }
        });

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            SseEventParser.parseInto(reader, publisher);
        } catch (IOException e) {
            if (!closed && !stopServerStream) {
                publisher.closeExceptionally(e);
            }
        } finally {
            publisher.close();
        }

        return lastEventId[0];
    }

    private HttpRequest buildPostRequest(String json) throws IOException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(mcpEndpoint)
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

        addAuthHeader(rb);
        addSessionHeader(rb);
        addProtocolVersionHeader(rb);

        return rb.build();
    }

    private void addAuthHeader(HttpRequest.Builder rb) {
        bearerTokenSupplier.get().ifPresent(token -> rb.header("Authorization", token));
    }

    private void addSessionHeader(HttpRequest.Builder rb) {
        String sid = this.sessionId;
        if (sid != null && SESSION_ID_PATTERN.matcher(sid).matches()) {
            rb.header("MCP-Session-Id", sid);
        }
    }

    private void addProtocolVersionHeader(HttpRequest.Builder rb) {
        String ver = this.protocolVersion;
        if (ver != null && !ver.isBlank()) {
            rb.header("MCP-Protocol-Version", ver);
        }
    }

    private boolean matchesRequest(Response response, RequestId requestId) {
        if (requestId == null) {
            return false;
        }
        RequestId responseId = null;
        if (response instanceof Response.SuccessResponse sr) {
            responseId = sr.id();
        } else if (response instanceof Response.ErrorResponse er) {
            responseId = er.id();
        }
        return requestId.equals(responseId);
    }

    private void sleepBackoff(int attempt) {
        // delays disabled per project policy
    }

    /**
     * Thrown when the server responds with a non-{@link Response} message to
     * a request that expected a response (e.g. server sent a Notification instead).
     */
    public static class InvalidResponseException extends RuntimeException {
        public InvalidResponseException(String message) {
            super(message);
        }
    }
}
