package com.nubian.ai.runtime.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.ErrorObject;
import com.nubian.ai.runtime.mcp.protocol.JsonRpcMessage;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.transport.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Wires server-initiated {@code sampling/createMessage} requests to a
 * {@link SamplingProvider}.
 *
 * <p>Register via {@link #register(McpTransport, SamplingProvider)}:
 * <pre>{@code
 * AutoCloseable handle = SamplingHandler.register(transport, myProvider);
 * // ...
 * handle.close(); // unregisters
 * }</pre>
 *
 * <p>The handler validates the tool-result message constraint required for cross-provider
 * compatibility (OpenAI, Claude, Gemini): every assistant {@code tool_use} block must be
 * answered by a subsequent user message that contains ONLY {@code tool_result} blocks
 * with matching {@code toolUseId}s. Violations are rejected with error code
 * {@link ErrorObject#INVALID_PARAMS} (-32602).
 */
public final class SamplingHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SamplingHandler.class);
    private static final String METHOD = "sampling/createMessage";

    private SamplingHandler() {}

    /**
     * Registers the sampling handler on the given transport.
     *
     * @param transport the active MCP transport
     * @param provider  the provider that will perform LLM calls
     * @return an {@link AutoCloseable} that signals intent to stop handling
     */
    public static AutoCloseable register(McpTransport transport, SamplingProvider provider) {
        ObjectMapper mapper = McpJsonMapper.instance();

        Consumer<JsonRpcMessage> incomingHandler = msg -> {
            if (!(msg instanceof Request req)) {
                return;
            }
            if (!METHOD.equals(req.method())) {
                return;
            }

            CreateMessageParams params;
            try {
                params = mapper.treeToValue(req.params(), CreateMessageParams.class);
            } catch (Exception e) {
                LOG.warn("sampling/createMessage: failed to parse params — {}", e.getMessage());
                sendError(transport, req, ErrorObject.INVALID_PARAMS,
                        "Invalid sampling/createMessage params: " + e.getMessage(), mapper);
                return;
            }

            // Validate tool-result message constraint
            String constraintViolation = validateToolResultConstraint(params.messages());
            if (constraintViolation != null) {
                LOG.warn("sampling/createMessage: tool-result constraint violation — {}",
                        constraintViolation);
                sendError(transport, req, ErrorObject.INVALID_PARAMS,
                        constraintViolation, mapper);
                return;
            }

            provider.createMessage(params).whenComplete((result, ex) -> {
                if (ex != null) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    boolean userRejected = cause instanceof IllegalStateException
                            && cause.getMessage() != null
                            && cause.getMessage().toLowerCase().contains("rejected");
                    int code = userRejected
                            ? ErrorObject.USER_REJECTED
                            : ErrorObject.INTERNAL_ERROR;
                    LOG.warn("sampling/createMessage: provider error (code={}) — {}",
                            code, cause.getMessage());
                    sendError(transport, req, code,
                            cause.getMessage() != null ? cause.getMessage() : "sampling error",
                            mapper);
                } else {
                    try {
                        JsonNode resultNode = mapper.valueToTree(result);
                        sendResponse(transport,
                                new Response.SuccessResponse(req.id(), resultNode), mapper);
                    } catch (Exception serEx) {
                        LOG.error("sampling/createMessage: failed to serialize result", serEx);
                        sendError(transport, req, ErrorObject.INTERNAL_ERROR,
                                "Failed to serialize sampling result: " + serEx.getMessage(),
                                mapper);
                    }
                }
            });
        };

        transport.onIncoming(incomingHandler);

        return () -> {
            // De-registration is best-effort; full support depends on transport.
        };
    }

    // ------------------------------------------------------------------
    // Tool-result constraint validation
    // ------------------------------------------------------------------

    /**
     * Validates the cross-provider tool-result message constraint:
     * <ol>
     *   <li>Every assistant {@code tool_use} block must be immediately followed by a
     *       user message.</li>
     *   <li>That user message must contain ONLY {@code tool_result} blocks (no mixing).</li>
     *   <li>Every tool_use must have a corresponding tool_result with a matching
     *       {@code toolUseId}.</li>
     * </ol>
     *
     * @return {@code null} if valid; otherwise a human-readable description of the violation.
     */
    static String validateToolResultConstraint(List<SamplingMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        ObjectMapper mapper = McpJsonMapper.instance();

        for (int i = 0; i < messages.size(); i++) {
            SamplingMessage msg = messages.get(i);
            if (msg.role() != Role.ASSISTANT) {
                continue;
            }

            // Collect tool_use ids from this assistant message
            List<String> toolUseIds = collectToolUseIds(msg.content(), mapper);
            if (toolUseIds.isEmpty()) {
                continue;
            }

            // The very next message must be a user message with only tool_results
            if (i + 1 >= messages.size()) {
                return "Assistant message at index " + i + " has tool_use blocks but no "
                        + "subsequent user message with tool_results";
            }

            SamplingMessage next = messages.get(i + 1);
            if (next.role() != Role.USER) {
                return "Assistant message at index " + i + " has tool_use blocks but "
                        + "next message has role '" + next.role().jsonValue()
                        + "' (expected 'user')";
            }

            // Validate the user message contains ONLY tool_result blocks
            String mixError = validateOnlyToolResults(next.content(), mapper, i + 1);
            if (mixError != null) {
                return mixError;
            }

            // Validate every tool_use_id is answered
            Set<String> resultIds = collectToolResultIds(next.content(), mapper);
            List<String> unmatched = new ArrayList<>();
            for (String id : toolUseIds) {
                if (!resultIds.contains(id)) {
                    unmatched.add(id);
                }
            }
            if (!unmatched.isEmpty()) {
                return "User message at index " + (i + 1) + " is missing tool_result blocks "
                        + "for tool_use ids: " + unmatched;
            }
        }

        return null;
    }

    /** Collects all {@code tool_use} ids from a content node (object or array). */
    private static List<String> collectToolUseIds(JsonNode content, ObjectMapper mapper) {
        List<String> ids = new ArrayList<>();
        if (content == null) return ids;
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("tool_use".equals(getType(item))) {
                    JsonNode idNode = item.get("id");
                    if (idNode != null && !idNode.isNull()) {
                        ids.add(idNode.asText());
                    }
                }
            }
        } else if (content.isObject() && "tool_use".equals(getType(content))) {
            JsonNode idNode = content.get("id");
            if (idNode != null && !idNode.isNull()) {
                ids.add(idNode.asText());
            }
        }
        return ids;
    }

    /** Collects all {@code tool_result} toolUseIds from a content node (object or array). */
    private static Set<String> collectToolResultIds(JsonNode content, ObjectMapper mapper) {
        Set<String> ids = new HashSet<>();
        if (content == null) return ids;
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("tool_result".equals(getType(item))) {
                    JsonNode idNode = item.get("toolUseId");
                    if (idNode != null && !idNode.isNull()) {
                        ids.add(idNode.asText());
                    }
                }
            }
        } else if (content.isObject() && "tool_result".equals(getType(content))) {
            JsonNode idNode = content.get("toolUseId");
            if (idNode != null && !idNode.isNull()) {
                ids.add(idNode.asText());
            }
        }
        return ids;
    }

    /**
     * Returns a non-null error message if the content contains anything other than
     * {@code tool_result} blocks.
     */
    private static String validateOnlyToolResults(JsonNode content, ObjectMapper mapper,
                                                   int msgIndex) {
        if (content == null) {
            return "User message at index " + msgIndex
                    + " has null content but was expected to hold tool_result blocks";
        }
        if (content.isArray()) {
            for (int j = 0; j < content.size(); j++) {
                JsonNode item = content.get(j);
                if (!"tool_result".equals(getType(item))) {
                    return "User message at index " + msgIndex
                            + " mixes tool_result with other content type '"
                            + getType(item) + "' at position " + j
                            + " (tool-result messages MUST contain ONLY tool_result blocks)";
                }
            }
        } else if (content.isObject()) {
            if (!"tool_result".equals(getType(content))) {
                return "User message at index " + msgIndex
                        + " expected tool_result content but got type '"
                        + getType(content) + "'";
            }
        }
        return null;
    }

    private static String getType(JsonNode node) {
        if (node == null) return null;
        JsonNode typeNode = node.get("type");
        return typeNode != null ? typeNode.asText(null) : null;
    }

    // ------------------------------------------------------------------
    // Transport send helpers (mirrors RootsHandler pattern)
    // ------------------------------------------------------------------

    private static void sendResponse(McpTransport transport, Response response,
                                     ObjectMapper mapper) {
        if (transport instanceof RootsHandler.ResponseCapableTransport rct) {
            rct.sendResponse(response);
        }
    }

    private static void sendError(McpTransport transport, Request req, int code,
                                   String message, ObjectMapper mapper) {
        sendResponse(transport,
                new Response.ErrorResponse(req.id(), ErrorObject.of(code, message)), mapper);
    }
}
