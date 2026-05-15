package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.transport.McpTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side facade for the MCP {@code prompts} capability.
 *
 * <p>Per the MCP spec (2025-11-25 / prompts):
 * <ul>
 *   <li>{@code prompts/list} — paginated discovery of available prompts.</li>
 *   <li>{@code prompts/get} — resolve a prompt to its {@link PromptMessage} sequence.</li>
 *   <li>{@code notifications/prompts/list_changed} — server pushes this when its prompt
 *       catalogue changes; clients should re-list.</li>
 * </ul>
 *
 * <p>This class is stateless with respect to transport instances; pass the active
 * {@link McpTransport} on each call. No Spring annotations are used.
 */
public class McpPromptsClient {

    private static final String METHOD_LIST  = "prompts/list";
    private static final String METHOD_GET   = "prompts/get";
    private static final String NOTIF_CHANGED = "notifications/prompts/list_changed";

    private final ObjectMapper mapper;
    private final AtomicLong idSequence = new AtomicLong(1);

    /** Constructs a client using the shared {@link McpJsonMapper} singleton. */
    public McpPromptsClient() {
        this(McpJsonMapper.instance());
    }

    /** Constructs a client with a custom {@link ObjectMapper}. */
    public McpPromptsClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // prompts/list
    // ------------------------------------------------------------------

    /**
     * Fetches one page of prompts.
     *
     * @param transport active MCP transport
     * @param cursor    opaque cursor from a previous result, or {@code null} for the first page
     * @return future resolving to the {@link ListPromptsResult} for this page
     */
    public CompletableFuture<ListPromptsResult> listPrompts(McpTransport transport, String cursor) {
        ListPromptsParams params = new ListPromptsParams(cursor);
        JsonNode paramsNode = mapper.valueToTree(params);
        Request request = new Request(nextId(), METHOD_LIST, paramsNode);
        return transport.send(request).thenApply(this::extractResult).thenApply(result ->
                mapper.convertValue(result, ListPromptsResult.class));
    }

    /**
     * Fetches all prompts by following pagination until exhausted.
     *
     * @param transport active MCP transport
     * @return future resolving to the complete flat list of {@link Prompt} objects
     */
    public CompletableFuture<List<Prompt>> listAllPrompts(McpTransport transport) {
        List<Prompt> accumulator = new ArrayList<>();
        return fetchAllPages(transport, null, accumulator);
    }

    private CompletableFuture<List<Prompt>> fetchAllPages(
            McpTransport transport, String cursor, List<Prompt> accumulator) {
        return listPrompts(transport, cursor).thenCompose(result -> {
            accumulator.addAll(result.prompts());
            if (result.hasMore()) {
                return fetchAllPages(transport, result.nextCursor(), accumulator);
            }
            return CompletableFuture.completedFuture(accumulator);
        });
    }

    // ------------------------------------------------------------------
    // prompts/get
    // ------------------------------------------------------------------

    /**
     * Resolves a named prompt to its {@link PromptMessage} sequence.
     *
     * @param transport active MCP transport
     * @param name      exact prompt name as returned by {@code prompts/list}
     * @param arguments optional map of argument name → value (may be {@code null})
     * @return future resolving to the {@link GetPromptResult}
     */
    public CompletableFuture<GetPromptResult> getPrompt(
            McpTransport transport, String name, Map<String, String> arguments) {
        GetPromptParams params = new GetPromptParams(name, arguments);
        JsonNode paramsNode = mapper.valueToTree(params);
        Request request = new Request(nextId(), METHOD_GET, paramsNode);
        return transport.send(request).thenApply(this::extractResult).thenApply(result ->
                mapper.convertValue(result, GetPromptResult.class));
    }

    // ------------------------------------------------------------------
    // notifications/prompts/list_changed
    // ------------------------------------------------------------------

    /**
     * Registers a handler invoked whenever the server emits a
     * {@code notifications/prompts/list_changed} notification.
     *
     * <p>The handler is called on the transport's notification delivery thread.
     * Clients should respond by calling {@link #listAllPrompts} to refresh their cache.
     *
     * @param transport active MCP transport
     * @param handler   callback to invoke on list-changed events
     */
    public void onListChanged(McpTransport transport, Runnable handler) {
        transport.onIncoming(message -> {
            if (message instanceof Notification n && NOTIF_CHANGED.equals(n.method())) {
                handler.run();
            }
        });
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private RequestId nextId() {
        return RequestId.of(idSequence.getAndIncrement());
    }

    private JsonNode extractResult(Response response) {
        if (response instanceof Response.SuccessResponse ok) {
            return ok.result();
        }
        if (response instanceof Response.ErrorResponse err) {
            throw new McpPromptsException(err.error().code(), err.error().message());
        }
        throw new McpPromptsException(-32603, "Unexpected response type: " + response.getClass().getName());
    }

    // ------------------------------------------------------------------
    // Exception
    // ------------------------------------------------------------------

    /**
     * Thrown when the server returns a JSON-RPC error response for a prompts request.
     */
    public static final class McpPromptsException extends RuntimeException {
        private final int code;

        public McpPromptsException(int code, String message) {
            super("[" + code + "] " + message);
            this.code = code;
        }

        /** The JSON-RPC error code. */
        public int code() {
            return code;
        }
    }
}
