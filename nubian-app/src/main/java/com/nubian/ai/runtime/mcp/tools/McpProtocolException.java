package com.nubian.ai.runtime.mcp.tools;

import com.nubian.ai.runtime.mcp.protocol.ErrorObject;

/**
 * Thrown when the MCP transport receives a JSON-RPC {@code error} response.
 *
 * <p>Per the MCP spec (2025-11-25), protocol errors differ from tool execution errors:
 * <ul>
 *   <li><b>Protocol error</b> ({@link McpProtocolException}) — unknown tool name, malformed
 *       request, or server crash. The request itself was wrong; the model cannot self-correct.</li>
 *   <li><b>Tool execution error</b> ({@link CallToolResult#isError()} == true) — API failure,
 *       input validation, business logic. The result goes back to the LLM for self-correction.</li>
 * </ul>
 *
 * @see ErrorObject
 */
public class McpProtocolException extends RuntimeException {

    private final ErrorObject errorObject;

    public McpProtocolException(ErrorObject errorObject) {
        super("MCP protocol error [" + errorObject.code() + "]: " + errorObject.message());
        this.errorObject = errorObject;
    }

    public McpProtocolException(ErrorObject errorObject, Throwable cause) {
        super("MCP protocol error [" + errorObject.code() + "]: " + errorObject.message(), cause);
        this.errorObject = errorObject;
    }

    /** The structured JSON-RPC error object from the server. */
    public ErrorObject getErrorObject() {
        return errorObject;
    }

    /** Shortcut for {@code getErrorObject().code()}. */
    public int getCode() {
        return errorObject.code();
    }
}
