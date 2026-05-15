package com.nubian.ai.runtime.mcp.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.tasks.TaskAugmentation;
import com.nubian.ai.runtime.mcp.transport.McpTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side implementation of the MCP Tools primitive.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code tools/list} — discover tools from a connected server</li>
 *   <li>{@code tools/call} — invoke a named tool</li>
 *   <li>{@code notifications/tools/list_changed} — react when the server's tool list changes</li>
 * </ul>
 *
 * <p><b>Error handling (two-mode split per MCP spec 2025-11-25):</b>
 * <ul>
 *   <li>Tool execution errors ({@code isError: true} in the result) are returned as-is.
 *       They carry actionable text for the LLM to self-correct — do NOT throw.</li>
 *   <li>JSON-RPC protocol errors (server returned an {@code error} object) are thrown as
 *       {@link McpProtocolException}. The model cannot self-correct these.</li>
 * </ul>
 *
 * <p>This class has no Spring annotations and no state beyond the shared Jackson mapper.
 * It is safe to use as a singleton.
 */
public class McpToolsClient {

    private static final String METHOD_LIST  = "tools/list";
    private static final String METHOD_CALL  = "tools/call";
    private static final String NOTIF_CHANGED = "notifications/tools/list_changed";

    private final ObjectMapper mapper;
    private final AtomicLong idCounter = new AtomicLong(1);

    /** Constructs a client using the shared MCP Jackson mapper singleton. */
    public McpToolsClient() {
        this.mapper = McpJsonMapper.instance();
    }

    /** Constructs a client with a custom Jackson mapper (for testing or DI). */
    public McpToolsClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // tools/list
    // ------------------------------------------------------------------

    /**
     * Send a {@code tools/list} request and return one page of results.
     *
     * @param transport the active MCP transport
     * @param cursor    pagination cursor from a previous response; {@code null} for the first page
     * @return a future completing with the page result
     */
    public CompletableFuture<ListToolsResult> listTools(McpTransport transport, String cursor) {
        ListToolsParams params = new ListToolsParams(cursor);
        JsonNode paramsNode = mapper.valueToTree(params);
        Request request = new Request(nextId(), METHOD_LIST, paramsNode);
        return transport.send(request).thenApply(this::extractListToolsResult);
    }

    /**
     * Paginate through all pages of {@code tools/list} and return the full flattened list.
     *
     * <p>Pagination continues until the server returns a {@code null} or absent
     * {@code nextCursor}. Each page is fetched sequentially.
     *
     * @param transport the active MCP transport
     * @return a future completing with all tool definitions
     */
    public CompletableFuture<List<ToolDefinition>> listAllTools(McpTransport transport) {
        List<ToolDefinition> accumulated = new ArrayList<>();
        return fetchPage(transport, null, accumulated);
    }

    private CompletableFuture<List<ToolDefinition>> fetchPage(
            McpTransport transport, String cursor, List<ToolDefinition> accumulated) {
        return listTools(transport, cursor).thenCompose(page -> {
            accumulated.addAll(page.tools());
            String next = page.nextCursor();
            if (next != null && !next.isBlank()) {
                return fetchPage(transport, next, accumulated);
            }
            return CompletableFuture.completedFuture(List.copyOf(accumulated));
        });
    }

    // ------------------------------------------------------------------
    // tools/call
    // ------------------------------------------------------------------

    /**
     * Invoke a tool by name with the given arguments.
     *
     * <p>Per the MCP spec:
     * <ul>
     *   <li>If the JSON-RPC response carries {@code isError: true}, the result is returned
     *       as-is — do NOT throw. The LLM receives the error content and can self-correct.</li>
     *   <li>If the JSON-RPC response is an {@code error} object (protocol error), throw
     *       {@link McpProtocolException}.</li>
     * </ul>
     *
     * @param transport the active MCP transport
     * @param name      tool name
     * @param arguments JSON object matching the tool's {@code inputSchema}; may be {@code null}
     * @return a future completing with the tool result (possibly {@code isError: true})
     * @throws McpProtocolException (wrapped in future failure) on JSON-RPC protocol errors
     */
    public CompletableFuture<CallToolResult> callTool(
            McpTransport transport, String name, JsonNode arguments) {
        CallToolParams params = new CallToolParams(name, arguments);
        JsonNode paramsNode = mapper.valueToTree(params);
        Request request = new Request(nextId(), METHOD_CALL, paramsNode);
        return transport.send(request).thenApply(this::extractCallToolResult);
    }

    /**
     * Invoke a tool with task-augmentation, enabling async polling via the tasks API.
     *
     * <p>When task augmentation is used, the server does not run the tool inline. Instead
     * it creates a background task and returns a {@code CreateTaskResult} in the response
     * body. Callers must poll the tasks API (via {@code tasks/get}) until the task reaches
     * a terminal state.
     *
     * <p>Like {@link #callTool}, protocol errors throw {@link McpProtocolException}. Tool
     * execution errors embedded in the result are returned as-is.
     *
     * @param transport the active MCP transport
     * @param name      tool name
     * @param arguments JSON object matching the tool's {@code inputSchema}; may be {@code null}
     * @param ttlMs     task time-to-live in milliseconds; converted to seconds for the wire format
     * @return a future completing with the raw result (contains a {@code CreateTaskResult} body)
     * @throws McpProtocolException (wrapped in future failure) on JSON-RPC protocol errors
     */
    public CompletableFuture<CallToolResult> callToolAsTask(
            McpTransport transport, String name, JsonNode arguments, long ttlMs) {
        long ttlSeconds = Math.max(0L, ttlMs / 1000L);
        TaskAugmentation task = new TaskAugmentation(ttlSeconds);
        CallToolParams params = new CallToolParams(name, arguments, task, null);
        JsonNode paramsNode = mapper.valueToTree(params);
        Request request = new Request(nextId(), METHOD_CALL, paramsNode);
        return transport.send(request).thenApply(this::extractCallToolResult);
    }

    // ------------------------------------------------------------------
    // notifications/tools/list_changed
    // ------------------------------------------------------------------

    /**
     * Register a handler that is called whenever the server sends a
     * {@code notifications/tools/list_changed} notification.
     *
     * <p>The handler is registered via {@link McpTransport#onIncoming}. Callers
     * typically use this to invalidate a cached tool list and re-fetch with
     * {@link #listAllTools}.
     *
     * @param transport the active MCP transport
     * @param handler   callback invoked on every list-changed notification
     */
    public void registerListChangedHandler(McpTransport transport, Runnable handler) {
        transport.onIncoming(message -> {
            if (message instanceof Notification n
                    && NOTIF_CHANGED.equals(n.method())) {
                handler.run();
            }
        });
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private RequestId nextId() {
        return RequestId.of(idCounter.getAndIncrement());
    }

    private ListToolsResult extractListToolsResult(Response response) {
        if (response instanceof Response.ErrorResponse err) {
            throw new McpProtocolException(err.error());
        }
        Response.SuccessResponse ok = (Response.SuccessResponse) response;
        try {
            return mapper.treeToValue(ok.result(), ListToolsResult.class);
        } catch (Exception e) {
            throw new McpProtocolException(
                    com.nubian.ai.runtime.mcp.protocol.ErrorObject.of(
                            com.nubian.ai.runtime.mcp.protocol.ErrorObject.INTERNAL_ERROR,
                            "Failed to deserialize tools/list result: " + e.getMessage()),
                    e);
        }
    }

    private CallToolResult extractCallToolResult(Response response) {
        if (response instanceof Response.ErrorResponse err) {
            // Protocol error — the request itself was wrong; throw, do not return to LLM.
            throw new McpProtocolException(err.error());
        }
        Response.SuccessResponse ok = (Response.SuccessResponse) response;
        try {
            return mapper.treeToValue(ok.result(), CallToolResult.class);
            // isError:true results are returned as-is per spec — LLM self-corrects.
        } catch (Exception e) {
            throw new McpProtocolException(
                    com.nubian.ai.runtime.mcp.protocol.ErrorObject.of(
                            com.nubian.ai.runtime.mcp.protocol.ErrorObject.INTERNAL_ERROR,
                            "Failed to deserialize tools/call result: " + e.getMessage()),
                    e);
        }
    }
}
