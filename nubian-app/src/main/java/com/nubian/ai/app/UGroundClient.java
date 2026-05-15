package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local UGround endpoint client. vLLM exposes an OpenAI-compatible chat endpoint
 * on :9001; this client sends the raw screenshot plus a target description and
 * expects a single x/y point back.
 */
@Component("appUGroundClient")
public final class UGroundClient {

    private static final Logger log = LoggerFactory.getLogger(UGroundClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<Thread, HttpURLConnection> LIVE = new ConcurrentHashMap<>();

    @Value("${nubian.uground.enabled:false}")
    private boolean enabled = false;

    @Value("${nubian.uground.base-url:http://localhost:20228/v1}")
    private String baseUrl = "http://localhost:20228/v1";

    @Value("${nubian.uground.api-key:}")
    private String apiKey = "";

    @Value("${nubian.uground.model:ByteDance-Seed/UI-TARS-1.5-7B}")
    private String model = "ByteDance-Seed/UI-TARS-1.5-7B";

    @Value("${nubian.uground.connect-timeout-ms:5000}")
    private int connectTimeoutMs = 5000;

    @Value("${nubian.uground.read-timeout-ms:60000}")
    private int readTimeoutMs = 60000;

    @Value("${nubian.uground.max-tokens:64}")
    private int maxTokens = 64;

    /** UGround V1 always emits in 0..1000 normalized space (per the model card).
     *  Default to "1000" so we always rescale; "auto" was unsafe because outputs
     *  like (157, 281) on a 1024² screen are valid pixel-looking values that the
     *  auto-detector silently skipped, causing every click to land in the wrong
     *  region. Override only if a future variant emits raw pixels. */
    @Value("${nubian.uground.coordinate-space:1000}")
    private String coordinateSpace = "1000";

    /** Resize the screenshot to this square size before sending to the grounder.
     *  0 (or non-positive) sends the raw bytes as-is. UI-TARS-1.5/Qwen-VL uses a
     *  smart-resize on the server side too, but bumping our submission to 1344²
     *  upsamples the 1024² sandbox screenshot which gives the vision encoder more
     *  pixels to localize fine controls. Coordinates returned by the model are in
     *  the resized space and scaled back to original-image pixels client-side. */
    @Value("${nubian.uground.resize-target:0}")
    private int resizeTarget = 0;

    /** Vote-mode: route every locate() through 3 parallel UI-TARS calls at
     *  resolutions 1024 / 1344 / 1000 and cluster the responses. Validated in
     *  the Python lab where the same prompt produced clicks ~140 px apart at
     *  different resolutions; majority agreement collapsed UI-TARS jitter.
     *  Default off until A/B verified end-to-end. */
    @Value("${nubian.uground.vote-mode:false}")
    private boolean voteMode = false;

    /** Cluster radius in 1024-pixel space — picks within this distance count
     *  as agreement. ~30 px ≈ a button / icon's worth of slack on a 1024² UI. */
    @Value("${nubian.uground.vote-cluster-px:30}")
    private int voteClusterPx = 30;

    /** Resolutions to sweep when voteMode is on. */
    @Value("#{'${nubian.uground.vote-sizes:1024,1344,1000}'.split(',')}")
    private java.util.List<String> voteSizesProp = java.util.List.of("1024", "1344", "1000");

    /** Telemetry hook so callers can subscribe to disagreement events. The
     *  Agent wires a consumer that emits a `grounding_disagreement` event so
     *  the planner can SEE that the click is uncertain and switch routes. */
    private volatile java.util.function.Consumer<DisagreementEvent> disagreementHook = ev -> {};

    public void onDisagreement(java.util.function.Consumer<DisagreementEvent> hook) {
        this.disagreementHook = hook == null ? ev -> {} : hook;
    }

    public record DisagreementEvent(String description,
            java.util.List<int[]> picks, int spreadPx, int chosenX, int chosenY) {}

    private static final String UGROUND_TEMPLATE =
            "Your task is to help the user identify the precise coordinates (x, y) of a specific "
                    + "area/element/object on the screen based on a description.\n\n"
                    + "- Your response should aim to point to the center or a representative point within "
                    + "the described area/element/object as accurately as possible.\n"
                    + "- For desktop menus, distinguish a parent menu row from a child submenu panel. "
                    + "If the description says the target is inside the right or left child submenu panel, "
                    + "ignore the parent row and point inside that child panel.\n"
                    + "- If the description is unclear or ambiguous, infer the most relevant area or element "
                    + "based on its likely context or purpose.\n"
                    + "- Your answer should be a single string (x, y) corresponding to the point of the "
                    + "interest.\n\n"
                    + "Description: %s\n\n"
                    + "Answer:";

    public boolean enabled() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }

    public String model() { return model; }

    public String baseUrl() { return baseUrl; }

    /** One-shot health probe used by the agent at run start. Returns true iff
     *  /v1/models answers 2xx within 5 s. */
    public boolean healthCheck() {
        if (!enabled()) return false;
        HttpURLConnection conn = null;
        try {
            URL u = URI.create(cleanBaseUrl(baseUrl) + "/models").toURL();
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            log.info("[uground.health] {}/models -> HTTP {}", cleanBaseUrl(baseUrl), code);
            return code / 100 == 2;
        } catch (Exception ex) {
            log.warn("[uground.health] failed: {}", ex.toString());
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    public GroundedPoint locate(byte[] screenshotPng, String elementDescription) {
        if (!enabled()) {
            throw new IllegalStateException("UGround disabled");
        }
        if (screenshotPng == null || screenshotPng.length == 0) {
            throw new IllegalArgumentException("UGround locate: empty screenshot");
        }
        if (elementDescription == null || elementDescription.isBlank()) {
            throw new IllegalArgumentException("UGround locate: empty element description");
        }
        if (voteMode) {
            return locateWithVote(screenshotPng, elementDescription);
        }

        ImageSize originalSize = imageSize(screenshotPng);
        byte[] payloadPng = screenshotPng;
        ImageSize modelSize = originalSize;
        if (resizeTarget > 0
                && (originalSize.width() != resizeTarget || originalSize.height() != resizeTarget)) {
            byte[] resized = resizePng(screenshotPng, resizeTarget);
            if (resized != null) {
                payloadPng = resized;
                modelSize = new ImageSize(resizeTarget, resizeTarget);
            }
        }
        ObjectNode body = buildRequest(payloadPng, elementDescription.trim());
        String url = cleanBaseUrl(baseUrl) + "/chat/completions";
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL u = URI.create(url).toURL();
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            byte[] payload = MAPPER.writeValueAsBytes(body);
            LIVE.put(Thread.currentThread(), conn);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int status = conn.getResponseCode();
            byte[] respBytes = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String respText = new String(respBytes, StandardCharsets.UTF_8);
            long elapsed = System.currentTimeMillis() - t0;
            if (status >= 400) {
                throw new IOException("UGround HTTP " + status + ": " + truncate(respText, 700));
            }
            String content = responseContent(respText);
            GroundedPoint point = parsePoint(content, modelSize.width(), modelSize.height());
            if (modelSize.width() != originalSize.width()
                    || modelSize.height() != originalSize.height()) {
                int scaledX = (int) Math.round((double) point.x()
                        * Math.max(1, originalSize.width() - 1)
                        / Math.max(1, modelSize.width() - 1));
                int scaledY = (int) Math.round((double) point.y()
                        * Math.max(1, originalSize.height() - 1)
                        / Math.max(1, modelSize.height() - 1));
                scaledX = clamp(scaledX, 0, Math.max(0, originalSize.width() - 1));
                scaledY = clamp(scaledY, 0, Math.max(0, originalSize.height() - 1));
                point = new GroundedPoint(scaledX, scaledY, point.raw());
            }
            log.info("[uground] {} -> ({},{}) in {}ms model={} sent={}x{}",
                    truncate(elementDescription, 80), point.x(), point.y(), elapsed, model,
                    modelSize.width(), modelSize.height());
            return point;
        } catch (java.io.InterruptedIOException ix) {
            throw new RuntimeException(new InterruptedException("UGround aborted: " + ix.getMessage()));
        } catch (IOException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException(new InterruptedException("UGround interrupted: " + ex.getMessage()));
            }
            throw new RuntimeException("UGround call failed: " + ex.getMessage(), ex);
        } finally {
            LIVE.remove(Thread.currentThread());
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Scene-detailed mode: ask UI-TARS to describe what's on screen as a short
     * paragraph instead of clicking. ~6 s, ~200 tokens, no coords. Use once at
     * task start (or on re-orient) to give the planner a fresh textual scene
     * description it can reason against without burning a vision call per step.
     *
     * Per the UI-TARS prompt-mode sweep: this is the "scene-detailed" mode —
     * too slow to use per click, but a clean one-shot for situational context.
     */
    public String describeScene(byte[] screenshotPng) {
        if (!enabled()) {
            throw new IllegalStateException("UGround disabled");
        }
        if (screenshotPng == null || screenshotPng.length == 0) {
            throw new IllegalArgumentException("UGround describeScene: empty screenshot");
        }
        ImageSize originalSize = imageSize(screenshotPng);
        byte[] payloadPng = screenshotPng;
        if (resizeTarget > 0
                && (originalSize.width() != resizeTarget || originalSize.height() != resizeTarget)) {
            byte[] resized = resizePng(screenshotPng, resizeTarget);
            if (resized != null) payloadPng = resized;
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.0);
        body.put("max_tokens", 256);
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode image = content.addObject();
        image.put("type", "image_url");
        image.putObject("image_url").put("url",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(payloadPng));
        content.addObject().put("type", "text").put("text",
                "Describe what is currently visible on this screen in 2-4 sentences. "
                        + "State (a) the active application, (b) any open dialog/panel and its title, "
                        + "(c) the visible toolbar groups (e.g. file/format/insert) and any salient "
                        + "icons by description (color/shape/position) since you may not have OCR for them. "
                        + "Do NOT output coordinates. Plain prose only.");

        String url = cleanBaseUrl(baseUrl) + "/chat/completions";
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL u = URI.create(url).toURL();
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            byte[] payload = MAPPER.writeValueAsBytes(body);
            LIVE.put(Thread.currentThread(), conn);
            try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
            int status = conn.getResponseCode();
            byte[] respBytes = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String respText = new String(respBytes, StandardCharsets.UTF_8);
            long elapsed = System.currentTimeMillis() - t0;
            if (status >= 400) {
                throw new IOException("UGround.describeScene HTTP " + status + ": " + truncate(respText, 700));
            }
            String description = responseContent(respText).trim();
            log.info("[uground.scene] {} chars in {}ms (model={})",
                    description.length(), elapsed, model);
            return description;
        } catch (java.io.InterruptedIOException ix) {
            throw new RuntimeException(new InterruptedException("UGround.describeScene aborted: " + ix.getMessage()));
        } catch (IOException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException(new InterruptedException("UGround.describeScene interrupted: " + ex.getMessage()));
            }
            throw new RuntimeException("UGround.describeScene failed: " + ex.getMessage(), ex);
        } finally {
            LIVE.remove(Thread.currentThread());
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }


    ObjectNode buildRequest(byte[] screenshotPng, String elementDescription) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.0);
        body.put("max_tokens", Math.max(8, maxTokens));
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode image = content.addObject();
        image.put("type", "image_url");
        image.putObject("image_url").put("url",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(screenshotPng));
        content.addObject().put("type", "text").put("text",
                String.format(UGROUND_TEMPLATE, elementDescription));
        return body;
    }

    String responseContent(String responseJson) throws IOException {
        JsonNode root = MAPPER.readTree(responseJson == null ? "{}" : responseJson);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return root.path("text").asText(responseJson == null ? "" : responseJson);
    }

    GroundedPoint parsePoint(String raw, int width, int height) throws IOException {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            if (node.has("x") && node.has("y")) {
                return normalize(node.path("x").asInt(), node.path("y").asInt(), width, height, text);
            }
        }
        List<Integer> nums = scanIntegers(text);
        if (nums.size() >= 2) {
            return normalize(nums.get(0), nums.get(1), width, height, text);
        }
        throw new IOException("UGround did not return coordinates: " + truncate(text, 240));
    }

    private GroundedPoint normalize(int x, int y, int width, int height, String raw) {
        int outX = x;
        int outY = y;
        String mode = coordinateSpace == null ? "auto" : coordinateSpace.trim().toLowerCase();
        if ("1000".equals(mode) || "normalized_1000".equals(mode) || "thousand".equals(mode)
                || shouldAutoScale(mode, x, y, width, height)) {
            outX = (int) Math.round((double) x * Math.max(1, width - 1) / 1000.0);
            outY = (int) Math.round((double) y * Math.max(1, height - 1) / 1000.0);
        }
        outX = clamp(outX, 0, Math.max(0, width - 1));
        outY = clamp(outY, 0, Math.max(0, height - 1));
        return new GroundedPoint(outX, outY, raw);
    }

    private static boolean shouldAutoScale(String mode, int x, int y, int width, int height) {
        if (!"auto".equals(mode)) return false;
        if (x < 0 || y < 0 || x > 1000 || y > 1000) return false;
        return x >= width || y >= height;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static List<Integer> scanIntegers(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            boolean negative = ch == '-';
            if (!negative && !isDigit(ch)) {
                i++;
                continue;
            }
            int j = negative ? i + 1 : i;
            if (j >= text.length() || !isDigit(text.charAt(j))) {
                i++;
                continue;
            }
            long value = 0;
            while (j < text.length() && isDigit(text.charAt(j))) {
                value = value * 10 + (text.charAt(j) - '0');
                if (value > Integer.MAX_VALUE) value = Integer.MAX_VALUE;
                j++;
            }
            out.add((int) (negative ? -value : value));
            i = j;
        }
        return out;
    }

    private static boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    private static ImageSize imageSize(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img != null) return new ImageSize(img.getWidth(), img.getHeight());
        } catch (IOException ignored) {
            // Fall through to conservative default.
        }
        return new ImageSize(1000, 1000);
    }

    /** Bicubic resize to {@code target}×{@code target} square PNG. Returns null on
     *  decode failure so the caller falls back to the original bytes. */
    private static byte[] resizePng(byte[] png, int target) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) return null;
            BufferedImage dst = new BufferedImage(target, target, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(src, 0, 0, target, target, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64 * 1024, png.length));
            ImageIO.write(dst, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            log.warn("[uground.resize] failed, falling back to original bytes: {}", ex.toString());
            return null;
        }
    }

    private static String cleanBaseUrl(String url) {
        String out = url == null ? "" : url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        return in.readAllBytes();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static void abortForThread(Thread target) {
        if (target == null) {
            log.warn("[uground.abort] no target thread supplied");
            return;
        }
        HttpURLConnection conn = LIVE.remove(target);
        if (conn == null) {
            log.warn("[uground.abort] no live connection for thread {}", target.getName());
            return;
        }
        try {
            conn.disconnect();
            log.warn("[uground.abort] disconnected live connection for thread {}", target.getName());
        } catch (Exception ex) {
            log.warn("[uground.abort] disconnect failed for thread {}: {}", target.getName(), ex.toString());
        }
    }

    /** Resolution-sweep vote. Sends the same description at 3 sizes (default
     *  1024 / 1344 / 1000) in parallel; returns the cluster centroid when 2+
     *  picks agree within voteClusterPx (~30). When all 3 disagree, fires the
     *  disagreementHook (Agent emits a `grounding_disagreement` event so the
     *  planner can switch routes) and returns the median by inter-distance. */
    GroundedPoint locateWithVote(byte[] screenshotPng, String elementDescription) {
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        for (String s : voteSizesProp) {
            try {
                int v = Integer.parseInt(s.trim());
                if (v > 0) sizes.add(v);
            } catch (NumberFormatException ignored) { /* skip malformed */ }
        }
        if (sizes.isEmpty()) sizes = java.util.List.of(1024, 1344, 1000);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(sizes.size());
        java.util.List<java.util.concurrent.Future<GroundedPoint>> futures = new java.util.ArrayList<>();
        for (int s : sizes) {
            futures.add(pool.submit(() -> locateAtSize(screenshotPng, elementDescription, s)));
        }
        pool.shutdown();
        java.util.List<GroundedPoint> picks = new java.util.ArrayList<>();
        for (java.util.concurrent.Future<GroundedPoint> f : futures) {
            try {
                GroundedPoint p = f.get(readTimeoutMs + 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (p != null) picks.add(p);
            } catch (Exception ex) {
                log.warn("[uground.vote] one resolution failed: {}", ex.toString());
            }
        }
        if (picks.isEmpty()) {
            throw new RuntimeException("UGround vote: all 3 calls failed");
        }
        if (picks.size() == 1) return picks.get(0);

        // Cluster: any pair within voteClusterPx counts as agreement. Pick the
        // largest cluster; if multiple tie, prefer the one containing 1024.
        int best = -1; int bestSize = 0;
        for (int i = 0; i < picks.size(); i++) {
            int sz = 1;
            for (int j = 0; j < picks.size(); j++) {
                if (i == j) continue;
                if (dist(picks.get(i), picks.get(j)) <= voteClusterPx) sz++;
            }
            if (sz > bestSize) { best = i; bestSize = sz; }
        }
        // Build cluster around `best`
        java.util.List<GroundedPoint> cluster = new java.util.ArrayList<>();
        cluster.add(picks.get(best));
        for (int j = 0; j < picks.size(); j++) {
            if (j != best && dist(picks.get(best), picks.get(j)) <= voteClusterPx) {
                cluster.add(picks.get(j));
            }
        }
        int spread = 0;
        for (int i = 0; i < picks.size(); i++)
            for (int j = i + 1; j < picks.size(); j++)
                spread = Math.max(spread, dist(picks.get(i), picks.get(j)));

        if (cluster.size() >= 2) {
            int cx = 0, cy = 0;
            for (GroundedPoint p : cluster) { cx += p.x(); cy += p.y(); }
            cx /= cluster.size(); cy /= cluster.size();
            log.info("[uground.vote] '{}' agreed at ({},{}) — cluster {}/{} spread {}px",
                    truncate(elementDescription, 80), cx, cy, cluster.size(), picks.size(), spread);
            return new GroundedPoint(cx, cy,
                    "vote-agreement(" + cluster.size() + "/" + picks.size() + ")");
        }
        // Disagreement: emit signal and return the geometric median.
        java.util.List<int[]> rawPicks = new java.util.ArrayList<>();
        for (GroundedPoint p : picks) rawPicks.add(new int[]{p.x(), p.y()});
        GroundedPoint median = pickMedian(picks);
        try {
            disagreementHook.accept(new DisagreementEvent(elementDescription,
                    rawPicks, spread, median.x(), median.y()));
        } catch (Exception ex) {
            log.warn("[uground.vote] disagreement hook threw: {}", ex.toString());
        }
        log.warn("[uground.vote] '{}' DISAGREED — picks {} spread {}px → median ({},{})",
                truncate(elementDescription, 80), rawPicks, spread, median.x(), median.y());
        return new GroundedPoint(median.x(), median.y(),
                "vote-disagreement(spread=" + spread + "px)");
    }

    /** Single locate call at a specific resize size; returns coords in 1024² space. */
    private GroundedPoint locateAtSize(byte[] screenshotPng, String elementDescription, int size) {
        ImageSize originalSize = imageSize(screenshotPng);
        byte[] payloadPng = screenshotPng;
        ImageSize modelSize = originalSize;
        if (size > 0 && (originalSize.width() != size || originalSize.height() != size)) {
            byte[] resized = resizePng(screenshotPng, size);
            if (resized != null) {
                payloadPng = resized;
                modelSize = new ImageSize(size, size);
            }
        }
        ObjectNode body = buildRequest(payloadPng, elementDescription.trim());
        String url = cleanBaseUrl(baseUrl) + "/chat/completions";
        HttpURLConnection conn = null;
        try {
            URL u = URI.create(url).toURL();
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            byte[] payload = MAPPER.writeValueAsBytes(body);
            try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
            int status = conn.getResponseCode();
            byte[] respBytes = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String respText = new String(respBytes, StandardCharsets.UTF_8);
            if (status >= 400) {
                throw new IOException("UGround HTTP " + status + ": " + truncate(respText, 700));
            }
            String content = responseContent(respText);
            GroundedPoint point = parsePoint(content, modelSize.width(), modelSize.height());
            // Scale back to original pixel space
            if (modelSize.width() != originalSize.width()) {
                int sx = (int) Math.round((double) point.x()
                        * Math.max(1, originalSize.width() - 1)
                        / Math.max(1, modelSize.width() - 1));
                int sy = (int) Math.round((double) point.y()
                        * Math.max(1, originalSize.height() - 1)
                        / Math.max(1, modelSize.height() - 1));
                sx = clamp(sx, 0, Math.max(0, originalSize.width() - 1));
                sy = clamp(sy, 0, Math.max(0, originalSize.height() - 1));
                return new GroundedPoint(sx, sy, point.raw() + "@" + size);
            }
            return new GroundedPoint(point.x(), point.y(), point.raw() + "@" + size);
        } catch (IOException ex) {
            throw new RuntimeException("UGround.locateAtSize(" + size + ") failed: " + ex.getMessage(), ex);
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static int dist(GroundedPoint a, GroundedPoint b) {
        int dx = a.x() - b.x(); int dy = a.y() - b.y();
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy));
    }

    private static GroundedPoint pickMedian(java.util.List<GroundedPoint> ps) {
        // Geometric median approximation: pick the point with smallest total distance to the others.
        int best = 0; long bestSum = Long.MAX_VALUE;
        for (int i = 0; i < ps.size(); i++) {
            long sum = 0;
            for (int j = 0; j < ps.size(); j++) if (i != j) sum += dist(ps.get(i), ps.get(j));
            if (sum < bestSum) { bestSum = sum; best = i; }
        }
        return ps.get(best);
    }

    public record GroundedPoint(int x, int y, String raw) {}

    private record ImageSize(int width, int height) {}
}
