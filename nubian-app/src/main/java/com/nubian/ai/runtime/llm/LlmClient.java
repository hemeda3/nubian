package com.nubian.ai.runtime.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Two-lane chat client. Both lanes target OpenRouter-compatible chat by default
 * with Gemini Flash Lite as the cheap baseline. Both speak the OpenAI chat
 * completions wire format. Tool calling and image_url multimodal content are supported.
 */
@Service
public class LlmClient {
    public enum Lane { VISION, TEXT }

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Map<Lane, LaneConfig> lanes = new LinkedHashMap<>();

    public LlmClient(
            ObjectMapper mapper,
            @Value("${nubian.llm.vision.base-url:https://openrouter.ai/api/v1}") String visionBaseUrl,
            @Value("${nubian.llm.vision.api-key:${OPENROUTER_API_KEY:}}") String visionKey,
            @Value("${nubian.llm.vision.model:google/gemini-2.5-flash-lite}") String visionModel,
            @Value("${nubian.llm.text.base-url:${nubian.llm.base-url:https://openrouter.ai/api/v1}}") String textBaseUrl,
            @Value("${nubian.llm.text.api-key:${nubian.llm.api-key:${OPENROUTER_API_KEY:}}}") String textKey,
            @Value("${nubian.llm.text.model:google/gemini-2.5-flash-lite}") String textModel,
            @Value("${nubian.llm.referer:http://127.0.0.1:7073/}") String referer,
            @Value("${nubian.llm.title:Nubian Computer Agent}") String title) {
        this.mapper = mapper;
        this.lanes.put(Lane.VISION, new LaneConfig(visionBaseUrl, visionKey, visionModel, referer, title));
        this.lanes.put(Lane.TEXT, new LaneConfig(textBaseUrl, textKey, textModel, referer, title));
        // Force HTTP/1.1 — JDK 21's HTTP/2 client pools connections and can serve
        // corrupted TLS state on the second request through a connection
        // (manifests as `bad_record_mac` mid-conversation, especially with the large
        // multimodal payloads we send). HTTP/1.1 + a fresh TCP connection per call
        // is rock-solid for our latency/throughput profile.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigured(Lane lane) {
        LaneConfig cfg = lanes.get(lane);
        return cfg != null && !cfg.apiKey().isBlank();
    }

    public String defaultModel(Lane lane) {
        return costSafeModel(lanes.get(lane).model());
    }

    public String baseUrl(Lane lane) {
        return lanes.get(lane).baseUrl();
    }

    public String renderRequestJson(ChatRequest request) throws LlmException {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(buildRequestBody(request));
        } catch (IOException ex) {
            throw new LlmException("Failed to encode chat request: " + ex.getMessage(), ex);
        }
    }

    /**
     * Per-thread map of the live HttpURLConnection so an external interrupt
     * (cancel button) can call {@code disconnect()} from a different thread —
     * JDK's HttpURLConnection ignores {@link Thread#interrupt()} for blocking
     * reads, so we have to abort the socket explicitly.
     */
    private static final java.util.concurrent.ConcurrentMap<Long, java.net.HttpURLConnection> LIVE_CONNS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Force-close any in-flight HttpURLConnection owned by the given thread. */
    public static void abortConnectionForThread(Thread t) {
        if (t == null) return;
        java.net.HttpURLConnection c = LIVE_CONNS.remove(t.getId());
        if (c != null) {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    public ChatResponse chat(ChatRequest request) throws LlmException {
        // Fast-fail: if the worker thread was interrupted between iterations,
        // don't even open a new HTTP connection.
        if (Thread.currentThread().isInterrupted()) {
            throw new LlmException("Chat call aborted — thread interrupted before send");
        }
        Lane lane = request.lane() == null ? Lane.VISION : request.lane();
        LaneConfig cfg = lanes.get(lane);
        if (cfg == null || cfg.apiKey().isBlank()) {
            throw new LlmException("LlmClient lane not configured: " + lane);
        }

        ObjectNode body = buildRequestBody(request);
        // OpenRouter providers queue/throttle huge max_tokens reservations on
        // 235B-class models. Cap at 4096 — tool calls don't need more.
        if (body.path("max_tokens").asInt(0) > 4096) body.put("max_tokens", 4096);

        // Local mlx-vlm shim doesn't implement OpenAI tool-calling. When we're
        // pointed at a localhost endpoint, strip tools/tool_choice and inject
        // a synthetic system message that instructs the model to emit a JSON
        // tool envelope as plain text. We parse it back into tool_calls below.
        boolean localMode = cfg.baseUrl().contains("127.0.0.1") || cfg.baseUrl().contains("localhost");
        if (localMode) {
            ArrayNode toolsForLocal = (ArrayNode) body.remove("tools");
            body.remove("tool_choice");
            body.remove("parallel_tool_calls");
            if (toolsForLocal != null && toolsForLocal.size() > 0) {
                injectLocalToolInstruction(body, toolsForLocal);
            }
        }

        String bodyJson;
        try {
            bodyJson = mapper.writeValueAsString(body);
        } catch (IOException ex) {
            throw new LlmException("Failed to encode chat request: " + ex.getMessage(), ex);
        }

        try {
            java.nio.file.Path dumpPath = java.nio.file.Paths.get("tmp/llm-debug/last-request.json");
            java.nio.file.Files.createDirectories(dumpPath.getParent());
            java.nio.file.Files.writeString(dumpPath, bodyJson);
        } catch (Exception ignored) { }

        long t0 = System.nanoTime();
        String url = cfg.baseUrl() + "/chat/completions";
        System.err.println("[LlmClient.chat] POST " + url
                + " bodyBytes=" + bodyJson.length() + " model=" + body.path("model").asText(""));

        // Plain blocking HttpURLConnection — no async, no thread pool, no
        // shared HttpClient. Same shape as a curl call.
        java.net.HttpURLConnection conn = null;
        long threadId = Thread.currentThread().getId();
        int status;
        String responseBody;
        try {
            java.net.URL u = java.net.URI.create(url).toURL();
            conn = (java.net.HttpURLConnection) u.openConnection();
            // Register the live connection so a cancel from the operator
            // thread can yank it via abortConnectionForThread().
            LIVE_CONNS.put(threadId, conn);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5_000);
            // Some provider calls spend minutes before the first token. The
            // timeout matches the flat app's LlmClient.
            conn.setReadTimeout(300_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey());
            if (cfg.baseUrl().contains("openrouter.ai")) {
                conn.setRequestProperty("HTTP-Referer", cfg.referer());
                conn.setRequestProperty("X-Title", cfg.title());
            }
            conn.setDoOutput(true);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
            }
            status = conn.getResponseCode();
            try (java.io.InputStream is = (status / 100 == 2 ? conn.getInputStream() : conn.getErrorStream())) {
                responseBody = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException ex) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.err.println("[LlmClient.chat] !!! HTTP error after " + ms + "ms: " + ex);
            throw new LlmException("HTTP error after " + ms + "ms calling " + url + ": " + ex.getMessage(), ex);
        } finally {
            LIVE_CONNS.remove(threadId);
            if (conn != null) conn.disconnect();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.err.println("[LlmClient.chat] HTTP " + status + " in " + elapsedMs + "ms bodyBytes=" + responseBody.length());
        if (status / 100 != 2) {
            throw new LlmException("LLM HTTP " + status + " on " + cfg.baseUrl() + ": " + responseBody);
        }
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (IOException ex) {
            throw new LlmException("Invalid JSON from LLM: " + ex.getMessage(), ex);
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String text = message.path("content").asText("");
        List<ToolCall> toolCalls = new ArrayList<>();
        if (localMode) {
            // Local mlx-vlm shim never emits native tool_calls — the model
            // returns its decision as a JSON envelope in the text content.
            // Parse it strictly: if the text isn't a valid envelope, raise —
            // no native-tool_calls fallback, no narration tolerance.
            ToolCall synth = parseLocalToolEnvelope(text);
            if (synth == null) {
                throw new LlmException("local-mode response did not contain a JSON tool envelope: "
                        + abbreviate(text, 400));
            }
            toolCalls.add(synth);
        } else {
            JsonNode toolCallsNode = message.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    String id = tc.path("id").asText("");
                    String name = tc.path("function").path("name").asText("");
                    String args = tc.path("function").path("arguments").asText("");
                    if (!name.isBlank()) {
                        toolCalls.add(new ToolCall(id, name, args));
                    }
                }
            }
        }
        String finishReason = choice.path("finish_reason").asText("");
        int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
        int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
        return new ChatResponse(text, toolCalls, finishReason, promptTokens, completionTokens, responseBody);
    }

    private ObjectNode buildRequestBody(ChatRequest request) {
        Lane lane = request.lane() == null ? Lane.VISION : request.lane();
        LaneConfig cfg = lanes.get(lane);
        ObjectNode body = mapper.createObjectNode();
        String requestedModel = request.model() == null || request.model().isBlank()
                ? cfg.model() : request.model();
        body.put("model", costSafeModel(requestedModel));
        body.put("temperature", request.temperature());
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        body.set("messages", buildMessages(request.messages()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.set("tools", buildTools(request.tools()));
            // "required" forces the model to emit at least one tool call per turn,
            // which is what an agent loop wants — Gemini 2.5 Flash with "auto"
            // happily narrates ("I will click File then New") without ever
            // producing a tool_call object, burning the no-tool strike budget.
            // The loop already exposes a `done` tool for legitimate stop turns.
            body.put("tool_choice", "required");
            body.put("parallel_tool_calls", true);
        }
        return body;
    }

    private ArrayNode buildMessages(List<ChatMessage> messages) {
        ArrayNode arr = mapper.createArrayNode();
        if (messages == null) {
            return arr;
        }
        for (ChatMessage msg : messages) {
            ObjectNode out = mapper.createObjectNode();
            out.put("role", msg.role());
            if (msg.toolCallId() != null && !msg.toolCallId().isBlank()) {
                out.put("tool_call_id", msg.toolCallId());
            }
            if (msg.name() != null && !msg.name().isBlank()) {
                out.put("name", msg.name());
            }
            if (msg.contentParts() != null && !msg.contentParts().isEmpty()) {
                ArrayNode parts = mapper.createArrayNode();
                for (ContentPart part : msg.contentParts()) {
                    ObjectNode p = mapper.createObjectNode();
                    if (part.imageBase64() != null) {
                        p.put("type", "image_url");
                        ObjectNode iu = mapper.createObjectNode();
                        iu.put("url", "data:" + part.imageMediaType() + ";base64," + part.imageBase64());
                        p.set("image_url", iu);
                    } else {
                        p.put("type", "text");
                        p.put("text", part.text() == null ? "" : part.text());
                    }
                    parts.add(p);
                }
                out.set("content", parts);
            } else {
                out.put("content", msg.text() == null ? "" : msg.text());
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode tcArr = mapper.createArrayNode();
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tco = mapper.createObjectNode();
                    tco.put("id", tc.id());
                    tco.put("type", "function");
                    ObjectNode fn = mapper.createObjectNode();
                    fn.put("name", tc.name());
                    fn.put("arguments", tc.argumentsJson() == null ? "{}" : tc.argumentsJson());
                    tco.set("function", fn);
                    tcArr.add(tco);
                }
                out.set("tool_calls", tcArr);
            }
            arr.add(out);
        }
        return arr;
    }

    private ArrayNode buildTools(List<ToolSpec> tools) {
        ArrayNode arr = mapper.createArrayNode();
        for (ToolSpec spec : tools) {
            ObjectNode tool = mapper.createObjectNode();
            tool.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", spec.name());
            fn.put("description", spec.description() == null ? "" : spec.description());
            try {
                JsonNode params = spec.parametersJsonSchema() == null || spec.parametersJsonSchema().isBlank()
                        ? mapper.readTree("{\"type\":\"object\",\"properties\":{}}")
                        : mapper.readTree(spec.parametersJsonSchema());
                fn.set("parameters", params);
            } catch (IOException ex) {
                ObjectNode fallback = mapper.createObjectNode();
                fallback.put("type", "object");
                fallback.set("properties", mapper.createObjectNode());
                fn.set("parameters", fallback);
            }
            tool.set("function", fn);
            arr.add(tool);
        }
        return arr;
    }

    public static String encodeImage(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes);
    }

    /**
     * Local-mode tool injection: appends a final system message that lists
     * the available tools and pins the output format to a single JSON envelope:
     * {@code {"tool":"<name>","args":{...}}}. The local mlx-vlm shim does not
     * implement OpenAI tool-calling, so this is the only contract that works.
     */
    private void injectLocalToolInstruction(ObjectNode body, ArrayNode toolsForLocal) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an agent that can call exactly one tool per turn.\n");
        sb.append("Available tools (JSON Schema for each):\n");
        for (JsonNode t : toolsForLocal) {
            JsonNode fn = t.path("function");
            sb.append("- ").append(fn.path("name").asText())
              .append(": ").append(fn.path("description").asText("")).append("\n")
              .append("  schema: ").append(fn.path("parameters").toString()).append("\n");
        }
        sb.append("\nReply with EXACTLY one JSON object on a single line, no prose, no code fences:\n");
        sb.append("{\"tool\":\"<tool_name>\",\"args\":{...}}\n");
        sb.append("Do not narrate. Do not wrap in markdown. Output the JSON only.");
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", sb.toString());
        ArrayNode messages = (ArrayNode) body.path("messages");
        messages.add(sys);
    }

    /**
     * Strict envelope parser. Accepts a single line {@code {"tool":"X","args":{...}}}
     * possibly wrapped in {@code ```json ... ```}. Returns {@code null} if no
     * envelope is found — the caller decides whether to raise.
     */
    private ToolCall parseLocalToolEnvelope(String text) {
        if (text == null) return null;
        String t = text.trim();
        // Strip ```json ... ``` fences if present.
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
            t = t.trim();
        }
        // Find the first '{' and matching '}' span at depth 0.
        int start = t.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        int end = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else {
                if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = i; break; }
                }
            }
        }
        if (end < 0) return null;
        String json = t.substring(start, end + 1);
        try {
            JsonNode env = mapper.readTree(json);
            String name = env.path("tool").asText("");
            if (name.isBlank()) return null;
            JsonNode args = env.path("args");
            String argsJson = args.isMissingNode() ? "{}" : args.toString();
            String id = "local_" + Long.toHexString(System.nanoTime());
            return new ToolCall(id, name, argsJson);
        } catch (IOException ex) {
            return null;
        }
    }

    private static String abbreviate(String value, int limit) {
        String safe = value == null ? "" : value;
        if (safe.length() <= limit) {
            return safe;
        }
        return safe.substring(0, Math.max(0, limit - 20)) + "\n...";
    }

    static String costSafeModel(String raw) {
        String model = raw == null ? "" : raw.trim();
        if (model.isBlank()) return "google/gemini-2.5-flash-lite";
        String lower = model.toLowerCase();
        if (premiumGemini(lower)) return "google/gemini-2.5-flash-lite";
        if (lower.contains("qwen3-vl-235b")) return "google/gemini-2.5-flash-lite";
        if (lower.contains("qwen3-vl-30b")) return "google/gemini-2.5-flash-lite";
        return model;
    }

    private static boolean premiumGemini(String lower) {
        if (lower == null || !lower.contains("gemini")) return false;
        return lower.contains("-pro")
                || lower.contains("/pro")
                || lower.contains(" pro")
                || lower.endsWith("pro");
    }

    private record LaneConfig(String baseUrl, String apiKey, String model, String referer, String title) {
        LaneConfig {
            baseUrl = trimOr(baseUrl, "");
            apiKey = trimOr(apiKey, "");
            model = trimOr(model, "");
            referer = trimOr(referer, "http://127.0.0.1:7073/");
            title = trimOr(title, "Nubian Computer Agent");
        }
    }

    private static String trimOr(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String t = value.trim();
        return t.isEmpty() ? fallback : t;
    }

    public record ChatRequest(
            Lane lane,
            String model,
            List<ChatMessage> messages,
            List<ToolSpec> tools,
            double temperature,
            int maxTokens) {
        public static ChatRequest vision(List<ChatMessage> messages, List<ToolSpec> tools) {
            return new ChatRequest(Lane.VISION, null, messages, tools, 0.2, 1024);
        }

        public static ChatRequest text(List<ChatMessage> messages, List<ToolSpec> tools) {
            return new ChatRequest(Lane.TEXT, null, messages, tools, 0.2, 1024);
        }
    }

    public record ChatMessage(
            String role,
            String text,
            List<ContentPart> contentParts,
            String toolCallId,
            String name,
            List<ToolCall> toolCalls) {
        public static ChatMessage system(String text) {
            return new ChatMessage("system", text, null, null, null, null);
        }

        public static ChatMessage user(String text) {
            return new ChatMessage("user", text, null, null, null, null);
        }

        public static ChatMessage userMultimodal(List<ContentPart> parts) {
            return new ChatMessage("user", null, parts, null, null, null);
        }

        public static ChatMessage assistant(String text) {
            return new ChatMessage("assistant", text, null, null, null, null);
        }

        public static ChatMessage assistantToolCalls(String text, List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", text == null ? "" : text, null, null, null, toolCalls);
        }

        public static ChatMessage tool(String toolCallId, String name, String text) {
            return new ChatMessage("tool", text, null, toolCallId, name, null);
        }

        public static ChatMessage toolMultimodal(String toolCallId, String name, List<ContentPart> parts) {
            return new ChatMessage("tool", null, parts, toolCallId, name, null);
        }
    }

    public record ContentPart(String text, String imageBase64, String imageMediaType) {
        public static ContentPart text(String text) {
            return new ContentPart(text, null, null);
        }

        public static ContentPart imagePng(byte[] data) {
            return new ContentPart(null, encodeImage(data), "image/png");
        }

        public static ContentPart image(byte[] data, String mediaType) {
            return new ContentPart(null, encodeImage(data), mediaType == null ? "image/png" : mediaType);
        }
    }

    public record ToolSpec(String name, String description, String parametersJsonSchema) {
        public static ToolSpec of(String name, String description, String parametersJsonSchema) {
            return new ToolSpec(name, description, parametersJsonSchema);
        }
    }

    public record ToolCall(String id, String name, String argumentsJson) {
    }

    public record ChatResponse(
            String text,
            List<ToolCall> toolCalls,
            String finishReason,
            int promptTokens,
            int completionTokens,
            String rawJson) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
