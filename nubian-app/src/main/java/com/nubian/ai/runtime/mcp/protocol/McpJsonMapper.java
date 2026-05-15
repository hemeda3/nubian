package com.nubian.ai.runtime.mcp.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;

/**
 * Pre-configured Jackson {@link ObjectMapper} for MCP JSON-RPC 2.0 serialization.
 *
 * <p>Configuration:
 * <ul>
 *   <li>Serializes only non-null fields ({@code NON_NULL})</li>
 *   <li>Ignores unknown properties (forward compatibility)</li>
 *   <li>Registers {@link JavaTimeModule} (Java 8 time types)</li>
 *   <li>Does NOT write dates as timestamps</li>
 *   <li>Registers polymorphic deserializers for {@link JsonRpcMessage} and
 *       {@link Response} driven by field presence, not type tags</li>
 * </ul>
 *
 * <p>Use {@link #instance()} to obtain the shared singleton. The mapper is
 * thread-safe after configuration.
 */
public final class McpJsonMapper {

    private static final ObjectMapper INSTANCE = configure(new ObjectMapper());

    private McpJsonMapper() {}

    /** Returns the shared, pre-configured singleton {@link ObjectMapper}. */
    public static ObjectMapper instance() {
        return INSTANCE;
    }

    /**
     * Applies MCP configuration to the supplied mapper and returns it.
     * Useful for integrating with a Spring-managed {@code ObjectMapper} bean.
     */
    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        SimpleModule mcpModule = new SimpleModule("mcp-protocol");
        mcpModule.addDeserializer(JsonRpcMessage.class, new JsonRpcMessageDeserializer());
        mcpModule.addDeserializer(Response.class, new ResponseDeserializer());
        mapper.registerModule(mcpModule);

        return mapper;
    }

    // ------------------------------------------------------------------
    // JsonRpcMessage deserializer
    //
    // Dispatch logic (field-presence driven, per JSON-RPC 2.0 spec):
    //   has "method" + has "id"   -> Request
    //   has "method" + no "id"    -> Notification
    //   has "result"              -> Response.SuccessResponse
    //   has "error"               -> Response.ErrorResponse
    // ------------------------------------------------------------------
    static final class JsonRpcMessageDeserializer extends StdDeserializer<JsonRpcMessage> {

        JsonRpcMessageDeserializer() {
            super(JsonRpcMessage.class);
        }

        @Override
        public JsonRpcMessage deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            boolean hasMethod = node.hasNonNull("method");
            boolean hasId     = node.has("id") && !node.get("id").isNull();
            boolean hasResult = node.has("result");
            boolean hasError  = node.hasNonNull("error");

            if (hasMethod && hasId) {
                return p.getCodec().treeToValue(node, Request.class);
            }
            if (hasMethod) {
                return p.getCodec().treeToValue(node, Notification.class);
            }
            if (hasResult) {
                return p.getCodec().treeToValue(node, Response.SuccessResponse.class);
            }
            if (hasError) {
                return p.getCodec().treeToValue(node, Response.ErrorResponse.class);
            }
            throw com.fasterxml.jackson.databind.exc.MismatchedInputException.from(
                    p, JsonRpcMessage.class,
                    "Cannot determine JSON-RPC message type: none of method/result/error fields present");
        }
    }

    // ------------------------------------------------------------------
    // Response deserializer — same logic but constrained to Response subtypes
    // ------------------------------------------------------------------
    static final class ResponseDeserializer extends StdDeserializer<Response> {

        ResponseDeserializer() {
            super(Response.class);
        }

        @Override
        public Response deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.has("result")) {
                return p.getCodec().treeToValue(node, Response.SuccessResponse.class);
            }
            if (node.hasNonNull("error")) {
                return p.getCodec().treeToValue(node, Response.ErrorResponse.class);
            }
            throw com.fasterxml.jackson.databind.exc.MismatchedInputException.from(
                    p, Response.class,
                    "Cannot determine Response type: neither 'result' nor 'error' field present");
        }
    }
}
