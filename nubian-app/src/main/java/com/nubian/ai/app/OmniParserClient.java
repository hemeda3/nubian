package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin HTTP client for the OmniParser-v2 REST server (Microsoft model bundle wrapped in
 * FastAPI; see {@code omniparser-server/} in this repo).
 *
 * <p>Each call POSTs a 1024² PNG to {@code /parse} and returns the labeled PNG (boxes +
 * numeric IDs burned in) plus the structured element list. The caller hands the labeled
 * PNG to the planner LLM and uses the element list to translate {@code click_box{id}}
 * into pixel coordinates.
 */
@Component("omniParserClient")
public final class OmniParserClient {

    private static final Logger log = LoggerFactory.getLogger(OmniParserClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Per-thread map of in-flight /parse futures so {@link #abortForThread} can cancel
     *  immediately on user cancel instead of waiting up to 30 s for the request timeout. */
    private static final ConcurrentHashMap<Thread, CompletableFuture<?>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    /** Cancel the OmniParser /parse call currently running on {@code target}, if any.
     *  Called from {@link Operator#cancel} so a user-cancel terminates the screenshot
     *  pipeline immediately instead of waiting for the upstream HTTP timeout. */
    public static void abortForThread(Thread target) {
        if (target == null) {
            log.warn("[omniparser.abort] no target thread supplied");
            return;
        }
        CompletableFuture<?> f = IN_FLIGHT.remove(target);
        if (f == null) {
            log.warn("[omniparser.abort] no in-flight parse for thread {}", target.getName());
            return;
        }
        boolean cancelled = f.cancel(true);
        log.warn("[omniparser.abort] cancel parse for thread {} -> {}", target.getName(), cancelled);
    }

    public OmniParserClient(@Value("${nubian.agent.omniparser.url:}") String url) {
        this.baseUrl = stripTrailingSlashes(url == null ? "" : url);
        this.enabled = !this.baseUrl.isBlank();
        if (this.enabled) {
            log.info("[omniparser] enabled, target={}", this.baseUrl);
        } else {
            log.info("[omniparser] disabled — set nubian.agent.omniparser.url to enable");
        }
    }

    private static String stripTrailingSlashes(String value) {
        String out = value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    public boolean enabled() { return enabled; }

    public String baseUrl() { return baseUrl; }

    /** One-shot health probe used by the agent at run start. Returns true iff
     *  the sidecar's /health endpoint answers 2xx within 5 s. */
    public boolean healthCheck() {
        if (!enabled) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/omni/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            log.info("[omniparser.health] {} -> HTTP {}", baseUrl + "/omni/health", code);
            return code / 100 == 2;
        } catch (Exception ex) {
            log.warn("[omniparser.health] failed: {}", ex.toString());
            return false;
        }
    }

    /** Resize a PNG so its longest side is at most {@code maxSide} px before /parse upload.
     *  Cuts /parse latency from ~5 s on a 3456² capture to ~1.5 s on a 1024² capture without
     *  losing element-detection recall (the model itself is resolution-tolerant). Falls back
     *  to the original bytes if scaling fails. */
    static byte[] resizeForParse(byte[] png, int maxSide) {
        if (png == null || png.length == 0 || maxSide <= 0) return png;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
            if (src == null) return png;
            int w = src.getWidth(), h = src.getHeight();
            int longest = Math.max(w, h);
            if (longest <= maxSide) return png;
            double scale = (double) maxSide / longest;
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length / 2);
            ImageIO.write(dst, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            return png;
        }
    }

    /** Fire one /health GET on startup so the cloudflared tunnel route is established before
     *  the first agent run. Without this, the first {@code /parse} call eats a 5-10 s
     *  cloudflared cold-start handshake on top of the actual server time. Async-style — runs
     *  on a daemon thread, never blocks startup, never throws. */
    @PostConstruct
    void prewarm() {
        if (!enabled) return;
        Thread t = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/health"))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                log.info("[omniparser] prewarm /health -> HTTP {} in {}ms · {}",
                        r.statusCode(), System.currentTimeMillis() - t0, truncate(r.body()));
            } catch (Exception ex) {
                log.warn("[omniparser] prewarm failed (non-fatal): {}", ex.toString());
            }
        }, "omniparser-prewarm");
        t.setDaemon(true);
        t.start();
    }

    public ParseResult parse(byte[] screenshotPng) {
        if (!enabled) {
            throw new IllegalStateException("OmniParser disabled — nubian.agent.omniparser.url not set");
        }
        if (screenshotPng == null || screenshotPng.length == 0) {
            throw new IllegalArgumentException("OmniParser.parse: empty screenshot");
        }
        byte[] resized = resizeForParse(screenshotPng, 1024);
        try {
            String boundary = "----nubian-" + UUID.randomUUID();
            byte[] body = multipart(boundary, resized);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/parse"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            Thread me = Thread.currentThread();
            CompletableFuture<HttpResponse<String>> fut =
                    http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            IN_FLIGHT.put(me, fut);
            HttpResponse<String> r;
            try {
                r = fut.get();
            } catch (java.util.concurrent.CancellationException ce) {
                throw new RuntimeException(new InterruptedException("OmniParser request cancelled"));
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                throw new RuntimeException("OmniParser request failed: " + cause, cause);
            } finally {
                IN_FLIGHT.remove(me, fut);
            }
            if (r.statusCode() / 100 != 2) {
                throw new RuntimeException("OmniParser HTTP " + r.statusCode() + ": " + truncate(r.body()));
            }
            JsonNode root = MAPPER.readTree(r.body());
            byte[] labeled = root.has("labeled_png_b64")
                    ? Base64.getDecoder().decode(root.get("labeled_png_b64").asText())
                    : null;
            JsonNode elementsNode = root.path("elements");
            List<Element> elements = new ArrayList<>(elementsNode.size());
            int W = root.path("image_size").path("w").asInt(1024);
            int H = root.path("image_size").path("h").asInt(1024);
            for (int i = 0; i < elementsNode.size(); i++) {
                elements.add(Element.from(i, elementsNode.get(i), W, H));
            }
            double totalS = root.path("timings").path("total_s").asDouble(-1);
            log.info("[omniparser] parsed {} elements in {}s", elements.size(), totalS);
            return new ParseResult(labeled, elements, totalS);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("OmniParser request failed: " + ex, ex);
        }
    }

    private static byte[] multipart(String boundary, byte[] png) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 256);
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"screen.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        out.write(head.getBytes());
        out.write(png);
        out.write(tail.getBytes());
        return out.toByteArray();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }

    public record ParseResult(byte[] labeledPng, List<Element> elements, double totalSeconds) {
    }

    public record Element(
            int id,
            String type,
            String content,
            boolean interactivity,
            String source,
            int x1, int y1, int x2, int y2) {

        public int centerX() { return (x1 + x2) / 2; }

        public int centerY() { return (y1 + y2) / 2; }

        static Element from(int id, JsonNode n, int W, int H) {
            JsonNode bbox = n.path("bbox");
            double bx1 = bbox.get(0).asDouble();
            double by1 = bbox.get(1).asDouble();
            double bx2 = bbox.get(2).asDouble();
            double by2 = bbox.get(3).asDouble();
            int x1 = (int) Math.round(bx1 <= 1.0 ? bx1 * W : bx1);
            int y1 = (int) Math.round(by1 <= 1.0 ? by1 * H : by1);
            int x2 = (int) Math.round(bx2 <= 1.0 ? bx2 * W : bx2);
            int y2 = (int) Math.round(by2 <= 1.0 ? by2 * H : by2);
            return new Element(
                    id,
                    n.path("type").asText(""),
                    n.path("content").asText(""),
                    n.path("interactivity").asBoolean(false),
                    n.path("source").asText(""),
                    x1, y1, x2, y2);
        }
    }
}
