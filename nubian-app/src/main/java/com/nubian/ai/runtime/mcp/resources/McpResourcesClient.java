package com.nubian.ai.runtime.mcp.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.JsonRpcMessage;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.transport.McpTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Client-side implementation of the MCP Resources primitive.
 *
 * <p>Covers all four resource operations defined by the MCP spec (2025-11-25):
 * <ul>
 *   <li>Discovery   — {@code resources/list}, {@code resources/templates/list}</li>
 *   <li>Reading     — {@code resources/read}</li>
 *   <li>Subscriptions — {@code resources/subscribe}, {@code resources/unsubscribe}</li>
 *   <li>Notifications — {@code notifications/resources/updated},
 *       {@code notifications/resources/list_changed}</li>
 * </ul>
 *
 * <p>This class is a plain POJO — no Spring annotations. It is safe to construct
 * multiple instances; each instance carries its own request-ID counter. The shared
 * {@link McpJsonMapper} singleton is used for serialization.
 */
public class McpResourcesClient {

    private static final Logger log = LoggerFactory.getLogger(McpResourcesClient.class);
    private static final String METHOD_LIST_RESOURCES          = "resources/list";
    private static final String METHOD_LIST_TEMPLATES          = "resources/templates/list";
    private static final String METHOD_READ_RESOURCE           = "resources/read";
    private static final String METHOD_SUBSCRIBE               = "resources/subscribe";
    private static final String METHOD_UNSUBSCRIBE             = "resources/unsubscribe";
    private static final String NOTIF_RESOURCES_UPDATED        = "notifications/resources/updated";
    private static final String NOTIF_RESOURCES_LIST_CHANGED   = "notifications/resources/list_changed";

    private final ObjectMapper objectMapper;
    private final AtomicLong idCounter = new AtomicLong(1);

    /** Constructs a client using the shared {@link McpJsonMapper} singleton. */
    public McpResourcesClient() {
        this.objectMapper = McpJsonMapper.instance();
    }

    /** Constructs a client with a custom {@link ObjectMapper} (useful for testing). */
    public McpResourcesClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Discovery
    // -------------------------------------------------------------------------

    /**
     * Fetches a single page of resources from the server.
     *
     * @param transport the active MCP transport
     * @param cursor    optional pagination cursor; pass {@code null} for the first page
     * @return a future that resolves to the page result
     */
    public CompletableFuture<ListResourcesResult> listResources(McpTransport transport,
                                                                 String cursor) {
        ListResourcesParams params = new ListResourcesParams(cursor);
        JsonNode paramsNode = serializeParams(params);
        Request request = new Request(nextId(), METHOD_LIST_RESOURCES, paramsNode);
        return transport.send(request)
                .thenApply(response -> deserializeResult(response, ListResourcesResult.class));
    }

    /**
     * Fetches ALL resources by following pagination cursors until exhausted.
     *
     * <p>Each page triggers one {@code resources/list} round-trip. The returned
     * future resolves only when no more pages remain.
     *
     * @param transport the active MCP transport
     * @return a future that resolves to the complete, flat list of resources
     */
    public CompletableFuture<List<Resource>> listAllResources(McpTransport transport) {
        List<Resource> accumulated = new ArrayList<>();
        return fetchResourcesPage(transport, null, accumulated);
    }

    private CompletableFuture<List<Resource>> fetchResourcesPage(McpTransport transport,
                                                                   String cursor,
                                                                   List<Resource> accumulated) {
        return listResources(transport, cursor).thenCompose(page -> {
            accumulated.addAll(page.resources());
            if (page.nextCursor() != null && !page.nextCursor().isBlank()) {
                return fetchResourcesPage(transport, page.nextCursor(), accumulated);
            }
            return CompletableFuture.completedFuture(List.copyOf(accumulated));
        });
    }

    /**
     * Fetches the list of URI templates from the server.
     *
     * @param transport the active MCP transport
     * @return a future that resolves to the template list result
     */
    public CompletableFuture<ListResourceTemplatesResult> listResourceTemplates(
            McpTransport transport) {
        Request request = new Request(nextId(), METHOD_LIST_TEMPLATES);
        return transport.send(request)
                .thenApply(response -> deserializeResult(response, ListResourceTemplatesResult.class));
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    /**
     * Reads the contents of the resource at the given URI.
     *
     * @param transport the active MCP transport
     * @param uri       the URI of the resource to read
     * @return a future that resolves to the read result containing one or more content items
     */
    public CompletableFuture<ReadResourceResult> readResource(McpTransport transport, String uri) {
        ReadResourceParams params = new ReadResourceParams(uri);
        JsonNode paramsNode = serializeParams(params);
        Request request = new Request(nextId(), METHOD_READ_RESOURCE, paramsNode);
        return transport.send(request)
                .thenApply(response -> deserializeResult(response, ReadResourceResult.class));
    }

    // -------------------------------------------------------------------------
    // Subscriptions
    // -------------------------------------------------------------------------

    /**
     * Subscribes to change notifications for the resource at {@code uri}.
     *
     * <p>After a successful call the server will send
     * {@code notifications/resources/updated} whenever the resource changes.
     * Register a handler with {@link #onResourceUpdated} to act on those notifications.
     *
     * @param transport the active MCP transport
     * @param uri       the URI of the resource to watch
     * @return a future that resolves to {@code null} on success
     */
    public CompletableFuture<Void> subscribe(McpTransport transport, String uri) {
        SubscribeParams params = new SubscribeParams(uri);
        JsonNode paramsNode = serializeParams(params);
        Request request = new Request(nextId(), METHOD_SUBSCRIBE, paramsNode);
        return transport.send(request).thenApply(response -> {
            assertNoError(response, METHOD_SUBSCRIBE);
            return null;
        });
    }

    /**
     * Cancels an existing subscription for the resource at {@code uri}.
     *
     * @param transport the active MCP transport
     * @param uri       the URI of the resource to stop watching
     * @return a future that resolves to {@code null} on success
     */
    public CompletableFuture<Void> unsubscribe(McpTransport transport, String uri) {
        UnsubscribeParams params = new UnsubscribeParams(uri);
        JsonNode paramsNode = serializeParams(params);
        Request request = new Request(nextId(), METHOD_UNSUBSCRIBE, paramsNode);
        return transport.send(request).thenApply(response -> {
            assertNoError(response, METHOD_UNSUBSCRIBE);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Notification listeners
    // -------------------------------------------------------------------------

    /**
     * Registers a handler that is invoked whenever the server sends a
     * {@code notifications/resources/updated} notification.
     *
     * <p>The handler receives the URI of the changed resource. Multiple handlers
     * may be registered on the same transport; all are called in registration order
     * (as guaranteed by {@link McpTransport#onIncoming}).
     *
     * @param transport the active MCP transport
     * @param handler   consumer invoked with the changed resource URI
     */
    public void onResourceUpdated(McpTransport transport, Consumer<String> handler) {
        transport.onIncoming(message -> {
            if (message instanceof Notification notif
                    && NOTIF_RESOURCES_UPDATED.equals(notif.method())) {
                try {
                    JsonNode params = notif.params();
                    if (params != null && params.hasNonNull("uri")) {
                        handler.accept(params.get("uri").asText());
                    }
                } catch (Exception ex) {
                    log.debug("onResourceUpdated notification handler failed: {}", ex.toString());
                    // Malformed notification — skip silently; do not crash the listener chain.
                }
            }
        });
    }

    /**
     * Registers a handler that is invoked whenever the server sends a
     * {@code notifications/resources/list_changed} notification, indicating that
     * the list of available resources has changed and callers should re-fetch.
     *
     * @param transport the active MCP transport
     * @param handler   runnable invoked on each list-changed notification
     */
    public void onListChanged(McpTransport transport, Runnable handler) {
        transport.onIncoming(message -> {
            if (message instanceof Notification notif
                    && NOTIF_RESOURCES_LIST_CHANGED.equals(notif.method())) {
                try {
                    handler.run();
                } catch (Exception ex) {
                    log.debug("onListChanged notification handler failed: {}", ex.toString());
                    // Handler threw — do not propagate into the transport layer.
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private RequestId nextId() {
        return RequestId.of(idCounter.getAndIncrement());
    }

    private JsonNode serializeParams(Object params) {
        return objectMapper.valueToTree(params);
    }

    private <T> T deserializeResult(Response response, Class<T> resultType) {
        if (response instanceof Response.ErrorResponse err) {
            throw new IllegalStateException(
                    "MCP error " + err.error().code() + ": " + err.error().message());
        }
        if (response instanceof Response.SuccessResponse ok) {
            try {
                return objectMapper.convertValue(ok.result(), resultType);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "Failed to deserialize " + resultType.getSimpleName() + ": " + ex.getMessage(), ex);
            }
        }
        throw new IllegalStateException("Unexpected Response type: " + response.getClass().getName());
    }

    private void assertNoError(Response response, String method) {
        if (response instanceof Response.ErrorResponse err) {
            throw new IllegalStateException(
                    "MCP error on " + method + " — code " + err.error().code()
                    + ": " + err.error().message());
        }
    }
}
