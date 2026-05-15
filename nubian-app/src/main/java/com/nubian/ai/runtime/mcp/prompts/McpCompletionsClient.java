package com.nubian.ai.runtime.mcp.prompts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.transport.McpTransport;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side facade for the MCP {@code completion} capability.
 *
 * <p>Per the MCP spec (2025-11-25 / completion): the {@code completion/complete} method
 * returns auto-complete candidates for a prompt argument or a URI template parameter.
 * It is intended to power interactive slash-command / URI-picker UIs.
 *
 * <p>This class is stateless with respect to transport instances; pass the active
 * {@link McpTransport} on each call. No Spring annotations are used.
 */
public class McpCompletionsClient {

    private static final String METHOD_COMPLETE = "completion/complete";

    private final ObjectMapper mapper;
    private final AtomicLong idSequence = new AtomicLong(1);

    /** Constructs a client using the shared {@link McpJsonMapper} singleton. */
    public McpCompletionsClient() {
        this(McpJsonMapper.instance());
    }

    /** Constructs a client with a custom {@link ObjectMapper}. */
    public McpCompletionsClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // completion/complete
    // ------------------------------------------------------------------

    /**
     * Requests auto-complete candidates for the given argument.
     *
     * <p>The {@code ref} identifies the prompt or resource template whose argument is
     * being completed. The {@code arg} carries the argument name and the partial value
     * the user has typed so far. {@code context} may supply already-resolved argument
     * values to enable context-sensitive completions; pass {@code null} to omit.
     *
     * @param transport active MCP transport
     * @param ref       discriminated reference to a prompt or resource template
     * @param arg       argument name and current partial value
     * @param context   optional map of already-bound argument values (may be {@code null})
     * @return future resolving to the {@link CompleteResult} (candidates + pagination hints)
     */
    public CompletableFuture<CompleteResult> complete(
            McpTransport transport,
            CompletionRef ref,
            CompletionArgument arg,
            Map<String, String> context) {
        CompleteParams params = new CompleteParams(ref, arg, context);
        JsonNode paramsNode = mapper.valueToTree(params);
        Request request = new Request(nextId(), METHOD_COMPLETE, paramsNode);
        return transport.send(request).thenApply(this::extractResult).thenApply(result ->
                mapper.convertValue(result, CompleteResult.class));
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
            throw new McpCompletionsException(err.error().code(), err.error().message());
        }
        throw new McpCompletionsException(-32603, "Unexpected response type: " + response.getClass().getName());
    }

    // ------------------------------------------------------------------
    // Exception
    // ------------------------------------------------------------------

    /**
     * Thrown when the server returns a JSON-RPC error response for a completion request.
     */
    public static final class McpCompletionsException extends RuntimeException {
        private final int code;

        public McpCompletionsException(int code, String message) {
            super("[" + code + "] " + message);
            this.code = code;
        }

        /** The JSON-RPC error code. */
        public int code() {
            return code;
        }
    }
}
