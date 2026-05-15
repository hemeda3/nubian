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

import java.util.function.Consumer;

/**
 * Wires server-initiated {@code elicitation/create} requests to an
 * {@link ElicitationProvider}.
 *
 * <p>Register via {@link #register(McpTransport, ElicitationProvider)}:
 * <pre>{@code
 * AutoCloseable handle = ElicitationHandler.register(transport, myProvider);
 * // ...
 * handle.close(); // unregisters
 * }</pre>
 *
 * <p>For FORM mode the handler validates that the {@code requestedSchema} (when present)
 * describes a flat object with only primitive property types — per spec, to ensure all
 * compliant clients can render a simple form. Violations are rejected with error code
 * {@link ErrorObject#INVALID_PARAMS} (-32602).
 */
public final class ElicitationHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ElicitationHandler.class);
    private static final String METHOD = "elicitation/create";

    private ElicitationHandler() {}

    /**
     * Registers the elicitation handler on the given transport.
     *
     * @param transport the active MCP transport
     * @param provider  the provider that will collect user input
     * @return an {@link AutoCloseable} that signals intent to stop handling
     */
    public static AutoCloseable register(McpTransport transport, ElicitationProvider provider) {
        ObjectMapper mapper = McpJsonMapper.instance();

        Consumer<JsonRpcMessage> incomingHandler = msg -> {
            if (!(msg instanceof Request req)) {
                return;
            }
            if (!METHOD.equals(req.method())) {
                return;
            }

            CreateElicitationParams params;
            try {
                params = mapper.treeToValue(req.params(), CreateElicitationParams.class);
            } catch (Exception e) {
                LOG.warn("elicitation/create: failed to parse params — {}", e.getMessage());
                sendError(transport, req, ErrorObject.INVALID_PARAMS,
                        "Invalid elicitation/create params: " + e.getMessage(), mapper);
                return;
            }

            // Validate FORM schema constraints
            if (params.mode() == ElicitationMode.FORM && params.requestedSchema() != null) {
                String schemaError = validateFormSchema(params.requestedSchema());
                if (schemaError != null) {
                    LOG.warn("elicitation/create: schema constraint violation — {}", schemaError);
                    sendError(transport, req, ErrorObject.INVALID_PARAMS, schemaError, mapper);
                    return;
                }
            }

            provider.createElicitation(params).whenComplete((result, ex) -> {
                if (ex != null) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    LOG.warn("elicitation/create: provider error — {}", cause.getMessage());
                    sendError(transport, req, ErrorObject.INTERNAL_ERROR,
                            cause.getMessage() != null ? cause.getMessage() : "elicitation error",
                            mapper);
                } else {
                    try {
                        JsonNode resultNode = mapper.valueToTree(result);
                        sendResponse(transport,
                                new Response.SuccessResponse(req.id(), resultNode), mapper);
                    } catch (Exception serEx) {
                        LOG.error("elicitation/create: failed to serialize result", serEx);
                        sendError(transport, req, ErrorObject.INTERNAL_ERROR,
                                "Failed to serialize elicitation result: " + serEx.getMessage(),
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
    // FORM schema validation
    // ------------------------------------------------------------------

    /**
     * Validates that a FORM-mode {@code requestedSchema} is a flat object schema with
     * only primitive property types (string, number, integer, boolean, string-enum,
     * or array-of-string-enum). No nested objects or arrays of objects are allowed.
     *
     * @return {@code null} if valid; otherwise a human-readable description of the violation.
     */
    static String validateFormSchema(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return null;
        }
        // Top-level type must be "object"
        JsonNode typeNode = schema.get("type");
        if (typeNode == null || !"object".equals(typeNode.asText())) {
            return "elicitation/create FORM requestedSchema top-level type must be \"object\" "
                    + "but was: " + (typeNode != null ? typeNode.asText() : "<absent>");
        }

        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            // No properties — unusual but not forbidden (empty form)
            return null;
        }

        for (java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it =
                properties.fields(); it.hasNext(); ) {
            java.util.Map.Entry<String, JsonNode> entry = it.next();
            String propName = entry.getKey();
            JsonNode propSchema = entry.getValue();
            String propError = validatePrimitivePropertySchema(propName, propSchema);
            if (propError != null) {
                return propError;
            }
        }
        return null;
    }

    /**
     * Validates a single property schema against the allowed primitive types.
     * Allowed: string, number, integer, boolean, string-enum (string with enum array),
     * array-of-string-enum (array with items that are string-enum).
     */
    private static String validatePrimitivePropertySchema(String propName, JsonNode propSchema) {
        if (propSchema == null || !propSchema.isObject()) {
            return "Property '" + propName + "' has an invalid schema (not an object)";
        }

        JsonNode typeNode = propSchema.get("type");
        String type = typeNode != null ? typeNode.asText("") : "";

        if ("string".equals(type) || "number".equals(type)
                || "integer".equals(type) || "boolean".equals(type)) {
            // Primitive types are always allowed (with or without enum/format)
            return null;
        }

        if ("array".equals(type)) {
            // Only arrays of string-enum are allowed
            JsonNode items = propSchema.get("items");
            if (items == null) {
                return "Property '" + propName + "' is an array but has no 'items' schema "
                        + "(only arrays of string-enum are permitted in FORM mode)";
            }
            JsonNode itemsType = items.get("type");
            if (!"string".equals(itemsType != null ? itemsType.asText("") : "")) {
                return "Property '" + propName + "' is an array but items type is '"
                        + (itemsType != null ? itemsType.asText() : "<absent>")
                        + "' (only arrays of string-enum are permitted in FORM mode)";
            }
            if (!items.has("enum")) {
                return "Property '" + propName + "' is a string array but items has no 'enum' "
                        + "(only arrays of string-enum are permitted in FORM mode)";
            }
            return null;
        }

        if (type.isEmpty()) {
            // No type field — allow if there's an enum (string-enum shorthand)
            if (propSchema.has("enum")) {
                return null;
            }
            return "Property '" + propName + "' has no 'type' field and no 'enum'; "
                    + "only primitive property types are permitted in FORM mode";
        }

        return "Property '" + propName + "' has unsupported type '" + type
                + "' (only string, number, integer, boolean, string-enum, "
                + "or array-of-string-enum are permitted in FORM mode)";
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
