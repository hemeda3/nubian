package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Component("appLlmClient")
public final class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String DEFAULT_LITE_MODEL = "google/gemini-2.5-flash-lite";
    private static final int MIN_REASONING_TOKENS = 8192;
    private static final int GEMINI_MAX_THINKING_TOKENS = 24576;

    private static final Map<Thread, HttpURLConnection> LIVE = new ConcurrentHashMap<>();
    static final String GLOBAL_TRACE_CONTRACT =
            "Every LLM response must include goal_link: 3-4 words showing how this helps the user's real final goal ROI. "
                    + "Every planned action must include goal_trace: current action because immediate UI effect because active checkpoint because final user goal. "
                    + "Example: change red field color because the form becomes readable because the form can be submitted because registration completes. "
                    + "If the chain is weak, choose another action. "
                    + "Every planned action must include assumption: what must exist; verified_by: why it exists and what verifies it.";

    public enum Lane { TEXT, VISION }

    public enum Role { SYSTEM, USER, ASSISTANT }

    /** Call role — controls generation config (thinking budget, JSON mode).
     *  PLANNER keeps full thinking budget. VERIFIER and EXTRACTOR force a small,
     *  thinking-free, JSON-mode response so atomic boolean/structured questions
     *  cannot truncate. BINARY_VERIFIER additionally pins a Gemini responseSchema
     *  to {ok:bool, reason:string} — protocol-level shape enforcement so the
     *  parser never has to infer the verdict from prose. */
    public enum CallRole { PLANNER, VERIFIER, EXTRACTOR, BINARY_VERIFIER }

    /** Per-call telemetry surfaced to the UI. Fired once per chat() call,
     *  on success AND on failure. No fields hidden — caller renders raw values. */
    public record LlmCallEvent(
            String callId,
            String role,
            String model,
            String lane,
            int promptTokens,
            int cachedTokens,
            int reasoningTokens,
            int completionTokens,
            int totalTokens,
            long requestBytes,
            long responseBytes,
            long elapsedMs,
            int httpStatus,
            String finishReason,
            String errorMessage) {
    }

    public interface CallTelemetry {
        void onComplete(LlmCallEvent event);
    }

    private static final ThreadLocal<CallTelemetry> TELEMETRY = new ThreadLocal<>();

    /** Bind a telemetry consumer for the current thread. Must be paired with
     *  {@link #unbindTelemetry()} in a finally block. Used by Agent.run to
     *  surface every LLM call's tokens to the SSE event stream. */
    public static void bindTelemetry(CallTelemetry telemetry) {
        if (telemetry == null) {
            TELEMETRY.remove();
        } else {
            TELEMETRY.set(telemetry);
        }
    }

    public static void unbindTelemetry() {
        TELEMETRY.remove();
    }

    private static void fireTelemetry(LlmCallEvent event) {
        CallTelemetry t = TELEMETRY.get();
        if (t == null) return;
        try {
            t.onComplete(event);
        } catch (RuntimeException tx) {
            log.debug("[llm] telemetry consumer threw: {}", tx.toString());
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong CALL_SEQ = new java.util.concurrent.atomic.AtomicLong(0L);

    public record Message(Role role, String text, List<byte[]> imagePngs) {
        public Message {
            imagePngs = imagePngs == null ? List.of() : List.copyOf(imagePngs);
        }

        public static Message system(String text) { return new Message(Role.SYSTEM, text, List.of()); }
        public static Message user(String text) { return new Message(Role.USER, text, List.of()); }
        public static Message userImage(String text, byte[] png) {
            return userImages(text, png == null || png.length == 0 ? List.of() : List.of(png));
        }
        public static Message userImages(String text, List<byte[]> pngs) {
            return new Message(Role.USER, text, pngs == null ? List.of() : pngs.stream()
                    .filter(p -> p != null && p.length > 0)
                    .toList());
        }
        public static Message assistant(String text) { return new Message(Role.ASSISTANT, text, List.of()); }
    }

    public record Reply(String text, String finishReason, int promptTokens, int completionTokens,
            int totalTokens, int reasoningChars, int cachedTokens, int reasoningTokens) {
        public Reply(String text, String finishReason, int promptTokens, int completionTokens) {
            this(text, finishReason, promptTokens, completionTokens, promptTokens + completionTokens, 0, 0, 0);
        }

        public Reply(String text, String finishReason, int promptTokens, int completionTokens,
                int totalTokens) {
            this(text, finishReason, promptTokens, completionTokens, totalTokens, 0, 0, 0);
        }

        public Reply(String text, String finishReason, int promptTokens, int completionTokens,
                int totalTokens, int reasoningChars) {
            this(text, finishReason, promptTokens, completionTokens, totalTokens, reasoningChars, 0, 0);
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final String defaultTextModel;
    private final String defaultVisionModel;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxOutputTokens;
    private final int minCallGapMs;

    @Value("${nubian.llm.reasoning.max-tokens:8192}")
    private int reasoningMaxTokens = MIN_REASONING_TOKENS;

    @Value("${nubian.llm.reasoning.exclude:true}")
    private boolean reasoningExclude = true;

    @Value("${nubian.gemini.direct-enabled:true}")
    private boolean geminiDirectEnabled = true;

    @Value("${nubian.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta";

    @Value("${GCP_API_KEY:}")
    private String geminiApiKeyFromEnv = "";

    @Value("${nubian.gemini.thinking-level:low}")
    private String geminiThinkingLevel = "low";

    @Value("${nubian.gemini.media-resolution:MEDIA_RESOLUTION_HIGH}")
    private String geminiMediaResolution = "MEDIA_RESOLUTION_HIGH";

    @Value("${nubian.gemini.omit-temperature-for-gemini3:true}")
    private boolean geminiOmitTemperatureForGemini3 = true;

    @Value("${nubian.gemini.min-output-tokens:512}")
    private int geminiMinOutputTokens = 512;

    private static final java.util.concurrent.atomic.AtomicLong LAST_CALL_END_MS =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public LlmClient(
            @Value("${nubian.llm.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${nubian.llm.api-key:${OPENROUTER_API_KEY:}}") String apiKey,
            @Value("${nubian.llm.text-model:google/gemini-2.5-flash-lite}") String textModel,
            @Value("${nubian.llm.vision-model:google/gemini-2.5-flash-lite}") String visionModel,
            @Value("${nubian.llm.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${nubian.llm.read-timeout-ms:300000}") int readTimeoutMs,
            @Value("${nubian.llm.max-output-tokens:32768}") int maxOutputTokens,
            @Value("${nubian.llm.min-call-gap-ms:3000}") int minCallGapMs) {
        this.baseUrl = clean(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.defaultTextModel = textModel;
        this.defaultVisionModel = visionModel;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxOutputTokens = maxOutputTokens;
        this.minCallGapMs = Math.max(0, minCallGapMs);
        if (this.apiKey.isEmpty()) {
            log.warn("[llm] no API key configured (set nubian.llm.api-key or OPENROUTER_API_KEY)");
        }
    }

    @PostConstruct
    void logGeminiConfig() {
        ResolvedGeminiKey key = resolveGeminiKey();
        log.info("[llm.gemini.config] direct={} base={} keyOrigin={} keyConfigured={} keyPrefix4={} keySha12={} thinkingLevel={} minOutputTokens={}",
                geminiDirectEnabled, clean(geminiBaseUrl), key.origin(), !key.value().isBlank(),
                keyPrefix4(key.value()), fingerprint(key.value()), geminiThinkingLevel, geminiMinOutputTokens);
    }

    public boolean geminiDirectEnabled() {
        return geminiDirectEnabled;
    }

    public String geminiBaseUrl() {
        return clean(geminiBaseUrl);
    }

    public String geminiKeySource() {
        return resolveGeminiKey().origin();
    }

    public boolean geminiKeyConfigured() {
        return !resolveGeminiKey().value().isBlank();
    }

    public String geminiKeyPrefix4() {
        return keyPrefix4(resolveGeminiKey().value());
    }

    public String geminiKeyFingerprint() {
        return fingerprint(resolveGeminiKey().value());
    }

    public String defaultModel(Lane lane) {
        return costSafeModel(lane == Lane.VISION ? defaultVisionModel : defaultTextModel);
    }

    private void throttle() {
        if (minCallGapMs <= 0) return;
        long earliest = LAST_CALL_END_MS.get() + minCallGapMs;
        long now = System.currentTimeMillis();
        long wait = earliest - now;
        if (wait <= 0) return;
        try {
            log.debug("[llm] throttle: sleeping {}ms to honor min-call-gap-ms={}", wait, minCallGapMs);
            Thread.sleep(wait);
        } catch (InterruptedException ix) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedException("LlmClient throttle interrupted"));
        }
    }

    public Reply chat(Lane lane, String modelOverride, List<Message> messages,
            double temperature, int maxOutputTokensRequest) {
        return chat(lane, modelOverride, null, null, messages, temperature, maxOutputTokensRequest, CallRole.PLANNER);
    }

    public Reply chat(Lane lane, String modelOverride, List<Message> messages,
            double temperature, int maxOutputTokensRequest, CallRole role) {
        return chat(lane, modelOverride, null, null, messages, temperature, maxOutputTokensRequest, role);
    }

    public Reply chat(Lane lane, String modelOverride,
            String baseUrlOverride, String apiKeyOverride,
            List<Message> messages,
            double temperature, int maxOutputTokensRequest) {
        return chat(lane, modelOverride, baseUrlOverride, apiKeyOverride, messages,
                temperature, maxOutputTokensRequest, CallRole.PLANNER);
    }

    public Reply chat(Lane lane, String modelOverride,
            String baseUrlOverride, String apiKeyOverride,
            List<Message> messages,
            double temperature, int maxOutputTokensRequest, CallRole role) {
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException(new InterruptedException("LlmClient.chat entry"));
        }
        throttle();
        CallRole useRole = role == null ? CallRole.PLANNER : role;
        String model = (modelOverride == null || modelOverride.isBlank())
                ? defaultModel(lane) : modelOverride.trim();
        model = costSafeModel(model);
        String useBase = (baseUrlOverride == null || baseUrlOverride.isBlank())
                ? baseUrl : clean(baseUrlOverride);
        String useKey = (apiKeyOverride == null || apiKeyOverride.isBlank())
                ? apiKey : apiKeyOverride.trim();

        if (shouldUseGeminiDirect(model, baseUrlOverride, apiKeyOverride)) {
            return chatGeminiDirect(lane, model, messages, temperature, maxOutputTokensRequest, useRole);
        }

        String callId = nextCallId();
        ObjectNode body = buildBody(model, messages, temperature, maxOutputTokensRequest, useRole);
        String url = useBase + "/chat/completions";
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        long requestBytes = 0L;
        long responseBytes = 0L;
        int status = 0;
        try {
            URL u = URI.create(url).toURL();
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (!useKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + useKey);
            conn.setRequestProperty("HTTP-Referer", "https://nubian.local/agent-demo");
            conn.setRequestProperty("X-Title", "Nubian Computer Demo");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            byte[] payload = MAPPER.writeValueAsBytes(body);
            requestBytes = payload.length;
            LIVE.put(Thread.currentThread(), conn);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            status = conn.getResponseCode();
            byte[] respBytes = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            responseBytes = respBytes.length;
            String respText = new String(respBytes, StandardCharsets.UTF_8);
            long elapsed = System.currentTimeMillis() - t0;
            if (status >= 400) {
                log.info("[llm] {} {} -> {} in {}ms ({} req bytes, {} resp bytes)",
                        lane, model, status, elapsed, payload.length, respBytes.length);
                String snippet = truncate(respText, 800);
                fireTelemetry(new LlmCallEvent(callId, useRole.name(), model, lane.name(),
                        0, 0, 0, 0, 0, requestBytes, responseBytes, elapsed, status, "", snippet));
                throw new IOException("LLM HTTP " + status + ": " + snippet);
            }
            Reply reply = parseReply(respText);
            log.info("[llm] {} {} -> {} in {}ms ({} req bytes, {} resp bytes) tokens prompt={} cached={} reasoning={} completion={} total={}",
                    lane, model, status, elapsed, payload.length, respBytes.length,
                    reply.promptTokens(), reply.cachedTokens(), reply.reasoningTokens(),
                    reply.completionTokens(), reply.totalTokens());
            fireTelemetry(new LlmCallEvent(callId, useRole.name(), model, lane.name(),
                    reply.promptTokens(), reply.cachedTokens(), reply.reasoningTokens(),
                    reply.completionTokens(), reply.totalTokens(),
                    requestBytes, responseBytes, elapsed, status, reply.finishReason(), null));
            return reply;
        } catch (java.io.InterruptedIOException ix) {
            throw new RuntimeException(new InterruptedException("LlmClient.chat aborted: " + ix.getMessage()));
        } catch (IOException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException(new InterruptedException("LlmClient.chat interrupted: " + ex.getMessage()));
            }
            long elapsed = System.currentTimeMillis() - t0;
            fireTelemetry(new LlmCallEvent(callId, useRole.name(), model, lane.name(),
                    0, 0, 0, 0, 0, requestBytes, responseBytes, elapsed, status, "", ex.getMessage()));
            throw new RuntimeException("LLM call failed: " + ex.getMessage(), ex);
        } finally {
            LAST_CALL_END_MS.set(System.currentTimeMillis());
            LIVE.remove(Thread.currentThread());
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception disconnectErr) {
                    log.debug("[llm] disconnect threw: {}", disconnectErr.toString());
                }
            }
        }
    }

    private Reply chatGeminiDirect(Lane lane, String model, List<Message> messages,
            double temperature, int maxOutputTokensRequest, CallRole role) {
        String modelId = geminiModelId(model);
        String callId = nextCallId();
        CallRole useRole = role == null ? CallRole.PLANNER : role;
        ObjectNode body = buildGeminiBody(modelId, messages, temperature, maxOutputTokensRequest, useRole);
        String url = clean(geminiBaseUrl) + "/models/" + urlPath(modelId) + ":generateContent";
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        long requestBytes = 0L;
        long responseBytes = 0L;
        int status = 0;
        try {
            URL u = URI.create(url).toURL();
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            ResolvedGeminiKey key = resolveGeminiKey();
            log.info("[llm.gemini.key] callId={} origin={} prefix4={} sha12={} configured={}",
                    callId, key.origin(), keyPrefix4(key.value()), fingerprint(key.value()), !key.value().isBlank());
            conn.setRequestProperty("x-goog-api-key", key.value());
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            byte[] payload = MAPPER.writeValueAsBytes(body);
            requestBytes = payload.length;
            LIVE.put(Thread.currentThread(), conn);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            status = conn.getResponseCode();
            byte[] respBytes = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            responseBytes = respBytes.length;
            String respText = new String(respBytes, StandardCharsets.UTF_8);
            long elapsed = System.currentTimeMillis() - t0;
            if (status >= 400) {
                log.info("[llm.gemini] {} {} -> {} in {}ms ({} req bytes, {} resp bytes)",
                        lane, modelId, status, elapsed, payload.length, respBytes.length);
                String snippet = truncate(respText, 800);
                fireTelemetry(new LlmCallEvent(callId, useRole.name(), modelId, lane.name(),
                        0, 0, 0, 0, 0, requestBytes, responseBytes, elapsed, status, "", snippet));
                throw new IOException("Gemini HTTP " + status + ": " + snippet);
            }
            Reply reply = parseGeminiReply(respText);
            log.info("[llm.gemini] {} {} -> {} in {}ms ({} req bytes, {} resp bytes) tokens prompt={} cached={} reasoning={} completion={} total={}",
                    lane, modelId, status, elapsed, payload.length, respBytes.length,
                    reply.promptTokens(), reply.cachedTokens(), reply.reasoningTokens(),
                    reply.completionTokens(), reply.totalTokens());
            fireTelemetry(new LlmCallEvent(callId, useRole.name(), modelId, lane.name(),
                    reply.promptTokens(), reply.cachedTokens(), reply.reasoningTokens(),
                    reply.completionTokens(), reply.totalTokens(),
                    requestBytes, responseBytes, elapsed, status, reply.finishReason(), null));
            return reply;
        } catch (java.io.InterruptedIOException ix) {
            throw new RuntimeException(new InterruptedException("LlmClient.gemini aborted: " + ix.getMessage()));
        } catch (IOException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException(new InterruptedException("LlmClient.gemini interrupted: " + ex.getMessage()));
            }
            long elapsed = System.currentTimeMillis() - t0;
            fireTelemetry(new LlmCallEvent(callId, useRole.name(), modelId, lane.name(),
                    0, 0, 0, 0, 0, requestBytes, responseBytes, elapsed, status, "", ex.getMessage()));
            throw new RuntimeException("Gemini call failed: " + ex.getMessage(), ex);
        } finally {
            LAST_CALL_END_MS.set(System.currentTimeMillis());
            LIVE.remove(Thread.currentThread());
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception disconnectErr) {
                    log.debug("[llm.gemini] disconnect threw: {}", disconnectErr.toString());
                }
            }
        }
    }

    ObjectNode buildBody(String model, List<Message> messages, double temperature, int maxOutputTokensRequest) {
        return buildBody(model, messages, temperature, maxOutputTokensRequest, CallRole.PLANNER);
    }

    ObjectNode buildBody(String model, List<Message> messages, double temperature,
            int maxOutputTokensRequest, CallRole role) {
        CallRole useRole = role == null ? CallRole.PLANNER : role;
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        int outBudget = Math.min(maxOutputTokensRequest > 0 ? maxOutputTokensRequest : maxOutputTokens, maxOutputTokens);
        body.put("max_tokens", outBudget);
        boolean atomic = useRole == CallRole.VERIFIER || useRole == CallRole.EXTRACTOR
                || useRole == CallRole.BINARY_VERIFIER;
        if (atomic) {
            if (useRole == CallRole.BINARY_VERIFIER) {
                // Reason-first ordering: the model must write its channel-cited
                // reasoning before committing to a boolean verdict, so the verdict
                // is a function of the reasoning rather than a self-contradicting
                // pre-commit. Mirrors the Gemini responseSchema propertyOrdering.
                ObjectNode rf = body.putObject("response_format");
                rf.put("type", "json_schema");
                ObjectNode js = rf.putObject("json_schema");
                js.put("name", "binary_verdict");
                js.put("strict", true);
                ObjectNode schema = js.putObject("schema");
                schema.put("type", "object");
                schema.put("additionalProperties", false);
                ObjectNode props = schema.putObject("properties");
                props.putObject("reason").put("type", "string");
                props.putObject("ok").put("type", "boolean");
                ArrayNode req = schema.putArray("required");
                req.add("reason");
                req.add("ok");
            } else {
                body.putObject("response_format").put("type", "json_object");
            }
        } else if (usesReasoningControls(model)) {
            ObjectNode reasoning = body.putObject("reasoning");
            if (usesGeminiThinkingBudget(model)) {
                reasoning.put("max_tokens", reasoningBudget());
            } else {
                reasoning.put("effort", "low");
            }
            reasoning.put("exclude", reasoningExclude);
        }
        ArrayNode msgs = body.putArray("messages");
        if (useRole == CallRole.PLANNER) {
            msgs.add(toJson(Message.system(GLOBAL_TRACE_CONTRACT)));
        }
        for (Message m : messages) msgs.add(toJson(m));
        return body;
    }

    ObjectNode buildGeminiBody(String modelId, List<Message> messages,
            double temperature, int maxOutputTokensRequest) {
        return buildGeminiBody(modelId, messages, temperature, maxOutputTokensRequest, CallRole.PLANNER);
    }

    ObjectNode buildGeminiBody(String modelId, List<Message> messages,
            double temperature, int maxOutputTokensRequest, CallRole role) {
        CallRole useRole = role == null ? CallRole.PLANNER : role;
        boolean atomic = useRole == CallRole.VERIFIER || useRole == CallRole.EXTRACTOR
                || useRole == CallRole.BINARY_VERIFIER;
        ObjectNode body = MAPPER.createObjectNode();
        StringBuilder system = new StringBuilder();
        ArrayNode contents = body.putArray("contents");
        if (messages != null) {
            for (Message m : messages) {
                if (m == null) continue;
                if (m.role() == Role.SYSTEM) {
                    if (m.text() != null && !m.text().isBlank()) {
                        if (system.length() > 0) system.append("\n\n");
                        system.append(m.text().trim());
                    }
                    continue;
                }
                ObjectNode content = contents.addObject();
                content.put("role", m.role() == Role.ASSISTANT ? "model" : "user");
                ArrayNode parts = content.putArray("parts");
                if (m.text() != null && !m.text().isEmpty()) {
                    parts.addObject().put("text", m.text());
                }
                for (byte[] png : m.imagePngs()) {
                    ObjectNode part = parts.addObject();
                    ObjectNode inline = part.putObject("inlineData");
                    inline.put("mimeType", "image/png");
                    inline.put("data", Base64.getEncoder().encodeToString(png));
                    String resolution = geminiMediaResolution == null ? "" : geminiMediaResolution.trim();
                    if (!resolution.isEmpty()) {
                        part.putObject("mediaResolution").put("level", resolution);
                    }
                }
                if (parts.isEmpty()) {
                    parts.addObject().put("text", "");
                }
            }
        }
        if (useRole == CallRole.PLANNER) {
            if (system.length() > 0) {
                system.append("\n\n").append(GLOBAL_TRACE_CONTRACT);
            } else {
                system.append(GLOBAL_TRACE_CONTRACT);
            }
        }
        if (system.length() > 0) {
            ObjectNode sys = body.putObject("systemInstruction");
            sys.putArray("parts").addObject().put("text", system.toString());
        }
        ObjectNode config = body.putObject("generationConfig");
        int outBudget = Math.min(maxOutputTokensRequest > 0 ? maxOutputTokensRequest : maxOutputTokens, maxOutputTokens);
        if (isGemini3Model(modelId)) {
            outBudget = Math.min(maxOutputTokens, Math.max(outBudget, Math.max(64, geminiMinOutputTokens)));
        }
        config.put("maxOutputTokens", outBudget);
        if (!(isGemini3Model(modelId) && geminiOmitTemperatureForGemini3)) {
            config.put("temperature", temperature);
        }
        if (atomic || wantsJsonResponse(system.toString(), messages)) {
            config.put("responseMimeType", "application/json");
        }
        if (useRole == CallRole.BINARY_VERIFIER) {
            // propertyOrdering puts `reason` BEFORE `ok` so the model has to
            // emit its channel-cited reasoning before committing to a verdict.
            // Without ordering the model commits to a boolean first and then
            // writes a reason that may directly contradict it (we observed
            // {ok:false, reason:"...the TODO is satisfied"} in a Chrome trace).
            // The verdict must be a function of the reasoning, not the other way.
            ObjectNode schema = config.putObject("responseSchema");
            schema.put("type", "OBJECT");
            ObjectNode props = schema.putObject("properties");
            props.putObject("reason").put("type", "STRING");
            props.putObject("ok").put("type", "BOOLEAN");
            ArrayNode req = schema.putArray("required");
            req.add("reason");
            req.add("ok");
            schema.putArray("propertyOrdering").add("reason").add("ok");
        }
        ObjectNode thinking = config.putObject("thinkingConfig");
        if (atomic) {
            if (isGemini3Model(modelId)) {
                // Gemini 3 thinking_level enum is {low, medium, high}; "none" is rejected.
                // "low" is the cheapest valid value for atomic JSON-shape calls.
                thinking.put("thinkingLevel", "low");
            } else if (usesGeminiThinkingBudget(modelId)) {
                // gemini-2.5-pro rejects thinkingBudget=0 ("This model only works
                // in thinking mode"). Use minimum 128 for pro; 0 for flash/lite.
                int budget = modelId != null && modelId.toLowerCase().contains("pro") ? 128 : 0;
                thinking.put("thinkingBudget", budget);
            } else {
                config.remove("thinkingConfig");
            }
        } else if (isGemini3Model(modelId)) {
            String level = geminiThinkingLevel == null || geminiThinkingLevel.isBlank()
                    ? "low" : geminiThinkingLevel.trim().toLowerCase();
            thinking.put("thinkingLevel", level);
        } else if (usesGeminiThinkingBudget(modelId)) {
            thinking.put("thinkingBudget", geminiThinkingBudget());
        } else {
            config.remove("thinkingConfig");
        }
        return body;
    }

    private static String nextCallId() {
        return "llm-" + Long.toString(CALL_SEQ.incrementAndGet(), 36);
    }

    private int reasoningBudget() {
        return Math.max(MIN_REASONING_TOKENS, reasoningMaxTokens);
    }

    private int geminiThinkingBudget() {
        return Math.min(reasoningBudget(), GEMINI_MAX_THINKING_TOKENS);
    }

    private static boolean wantsJsonResponse(String system, List<Message> messages) {
        if (containsJsonInstruction(system)) return true;
        if (messages == null) return false;
        for (Message message : messages) {
            if (message != null && containsJsonInstruction(message.text())) return true;
        }
        return false;
    }

    private static boolean containsJsonInstruction(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        return lower.contains("output only json")
                || lower.contains("json object only")
                || lower.contains("return exactly one json object")
                || lower.contains("return the next action json only")
                || lower.contains("return a json object only");
    }

    public static void abortForThread(Thread target) {
        if (target == null) {
            log.warn("[llm.abort] no target thread supplied");
            return;
        }
        HttpURLConnection conn = LIVE.remove(target);
        if (conn == null) {
            log.warn("[llm.abort] no live connection for thread {}", target.getName());
            return;
        }
        try {
            conn.disconnect();
            log.warn("[llm.abort] disconnected live connection for thread {}", target.getName());
        } catch (Exception ex) {
            log.warn("[llm.abort] disconnect failed for thread {}: {}", target.getName(), ex.toString());
        }
    }

    static String costSafeModel(String raw) {
        String model = raw == null ? "" : raw.trim();
        if (model.isBlank()) return DEFAULT_LITE_MODEL;
        String lower = model.toLowerCase();
        // Cost guards disabled while validating with gemini-2.5-pro on the
        // local agent. Re-enable individually if a specific model causes spend.
        // if (premiumGemini(lower)) return DEFAULT_LITE_MODEL;
        // if (lower.contains("qwen3-vl-235b")) return DEFAULT_LITE_MODEL;
        // if (lower.contains("qwen3-vl-30b")) return DEFAULT_LITE_MODEL;
        return model;
    }

    private static boolean premiumGemini(String lower) {
        if (lower == null || !lower.contains("gemini")) return false;
        return lower.contains("-pro")
                || lower.contains("/pro")
                || lower.contains(" pro")
                || lower.endsWith("pro");
    }

    private ObjectNode toJson(Message m) {
        ObjectNode o = MAPPER.createObjectNode();
        switch (m.role()) {
            case SYSTEM -> o.put("role", "system");
            case USER -> o.put("role", "user");
            case ASSISTANT -> o.put("role", "assistant");
        }
        if (!m.imagePngs().isEmpty()) {
            ArrayNode parts = o.putArray("content");
            if (m.text() != null && !m.text().isEmpty()) {
                ObjectNode text = parts.addObject();
                text.put("type", "text");
                text.put("text", m.text());
            }
            for (byte[] png : m.imagePngs()) {
                ObjectNode img = parts.addObject();
                img.put("type", "image_url");
                ObjectNode url = img.putObject("image_url");
                url.put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            }
        } else {
            o.put("content", m.text() == null ? "" : m.text());
        }
        return o;
    }

    Reply parseGeminiReply(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode candidate = root.path("candidates").path(0);
            JsonNode parts = candidate.path("content").path("parts");
            StringBuilder text = new StringBuilder();
            int reasoningChars = 0;
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    String s = part.path("text").asText("");
                    if (s.isEmpty()) continue;
                    if (part.path("thought").asBoolean(false)) {
                        reasoningChars += s.length();
                    } else {
                        text.append(s);
                    }
                }
            }
            String finish = candidate.path("finishReason").asText("");
            JsonNode usage = root.path("usageMetadata");
            int promptTok = usage.path("promptTokenCount").asInt(0);
            int compTok = usage.path("candidatesTokenCount").asInt(0);
            int totalTok = usage.path("totalTokenCount").asInt(promptTok + compTok);
            int thoughtTok = usage.path("thoughtsTokenCount").asInt(0);
            int cachedTok = usage.path("cachedContentTokenCount").asInt(0);
            return new Reply(stripThink(text.toString()), finish, promptTok, compTok, totalTok,
                    Math.max(reasoningChars, thoughtTok), cachedTok, thoughtTok);
        } catch (Exception ex) {
            throw new RuntimeException("Could not parse Gemini response: " + ex.getMessage()
                    + " body=" + truncate(json, 400), ex);
        }
    }

    Reply parseReply(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode choice = root.path("choices").path(0);
            JsonNode msg = choice.path("message");
            String text = msg.path("content").asText("");
            String reasoning = msg.path("reasoning").asText("");
            if (!reasoning.isEmpty()) {
                log.info("[llm] reasoning trace: {} chars (separate field, not parsed for tools)",
                        reasoning.length());
            }
            text = stripThink(text);
            String finish = choice.path("finish_reason").asText("");
            JsonNode usage = root.path("usage");
            int promptTok = usage.path("prompt_tokens").asInt(0);
            int compTok = usage.path("completion_tokens").asInt(0);
            int totalTok = usage.path("total_tokens").asInt(promptTok + compTok);
            int cachedTok = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
            int reasoningTok = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
            return new Reply(text, finish, promptTok, compTok, totalTok, reasoning.length(),
                    cachedTok, reasoningTok);
        } catch (Exception ex) {
            throw new RuntimeException("Could not parse LLM response: " + ex.getMessage()
                    + " body=" + truncate(json, 400), ex);
        }
    }

    private static boolean isThinkingModel(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("thinking") || m.contains("reasoner")
                || m.endsWith("-think") || m.contains(":thinking");
    }

    private static boolean usesReasoningControls(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return isThinkingModel(model) || m.contains("gemini-2.5") || m.contains("gemini-3");
    }

    private static boolean usesGeminiThinkingBudget(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("gemini-2.5");
    }

    static boolean isGeminiModel(String model) {
        if (model == null) return false;
        String m = model.trim().toLowerCase();
        return m.startsWith("gemini-")
                || m.startsWith("google/gemini-")
                || m.startsWith("models/gemini-");
    }

    private boolean shouldUseGeminiDirect(String model, String baseUrlOverride, String apiKeyOverride) {
        if (!geminiDirectEnabled || !isGeminiModel(model)) return false;
        if (baseUrlOverride != null && !baseUrlOverride.isBlank()) return false;
        if (apiKeyOverride != null && !apiKeyOverride.isBlank()) return false;
        if (!resolveGeminiKey().value().isBlank()) return true;
        log.warn("[llm.gemini] {} is a Gemini model but no Gemini API key is configured; falling back to OpenRouter", model);
        return false;
    }

    static String geminiModelId(String model) {
        if (model == null) return "";
        String m = model.trim();
        String lower = m.toLowerCase();
        if (lower.startsWith("google/")) return m.substring("google/".length());
        if (lower.startsWith("models/")) return m.substring("models/".length());
        return m;
    }

    private static boolean isGemini3Model(String model) {
        if (model == null) return false;
        return geminiModelId(model).toLowerCase().startsWith("gemini-3");
    }

    private static String stripThink(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        String out = text;
        while (true) {
            String lower = out.toLowerCase();
            int start = lower.indexOf("<think");
            if (start < 0) break;
            int openEnd = lower.indexOf('>', start);
            if (openEnd < 0) {
                out = out.substring(0, start);
                break;
            }
            int close = lower.indexOf("</think", openEnd + 1);
            if (close < 0) {
                out = out.substring(0, start);
                break;
            }
            int closeEnd = lower.indexOf('>', close);
            if (closeEnd < 0) {
                out = out.substring(0, start);
                break;
            }
            out = out.substring(0, start) + out.substring(closeEnd + 1);
        }
        return out.trim();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try (InputStream s = in) {
            return s.readAllBytes();
        }
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "…(+" + (s.length() - n) + ")";
    }

    private static String clean(String url) {
        if (url == null || url.isBlank()) return "https://openrouter.ai/api/v1";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String keyPrefix4(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(4, trimmed.length()));
    }

    private record ResolvedGeminiKey(String value, String origin) {}

    private ResolvedGeminiKey resolveGeminiKey() {
        for (Path path : List.of(
                Path.of("config", "application-dev.properties"),
                Path.of("..", "config", "application-dev.properties"))) {
            String value = readGcpApiKey(path);
            if (!value.isBlank()) {
                return new ResolvedGeminiKey(value, "file:" + path.normalize());
            }
        }
        String env = geminiApiKeyFromEnv == null ? "" : geminiApiKeyFromEnv.trim();
        if (!env.isBlank()) {
            return new ResolvedGeminiKey(env, "env:GCP_API_KEY");
        }
        return new ResolvedGeminiKey("", "unset");
    }

    private static String readGcpApiKey(Path path) {
        if (path == null || !Files.isRegularFile(path)) return "";
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(path.toFile())) {
            properties.load(in);
            String value = properties.getProperty("GCP_API_KEY", "");
            return value == null ? "" : value.trim();
        } catch (IOException ex) {
            log.debug("[llm.gemini.config] failed to read {}: {}", path, ex.toString());
            return "";
        }
    }

    private static String fingerprint(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < digest.length && out.length() < 12; i++) {
                String hex = Integer.toHexString(digest[i] & 0xff);
                if (hex.length() == 1) out.append('0');
                out.append(hex);
            }
            return out.substring(0, Math.min(12, out.length()));
        } catch (NoSuchAlgorithmException ex) {
            return "sha256-error";
        }
    }

    private static String urlPath(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
