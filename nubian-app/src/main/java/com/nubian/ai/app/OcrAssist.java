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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Click-feedback helper.
 * <p>
 * After (or before) the agent emits a click, this component crops a small region
 * around the planned (x,y), POSTs it to a PP-OCRv5 HTTP service that returns text
 * detections with polygon coordinates, numbers each detection 1..N, and renders an
 * annotated crop image with green boxes labelled "1", "2", ... so the LLM can see
 * exactly which OCR box corresponds to which line of text. Coordinates returned
 * are translated back into ABSOLUTE screen pixels.
 * <p>
 * Tools.java keeps a reference to the most recent OcrResult and exposes a
 * {@code click_box} tool that clicks {@code boxes[N].center()} directly, so the
 * model can pick a numbered target instead of guessing pixels.
 */
@Component("appOcrAssist")
public final class OcrAssist {

    private static final Logger log = LoggerFactory.getLogger(OcrAssist.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final boolean enabled;
    private final int cropW;
    private final int cropH;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public OcrAssist(
            @Value("${nubian.agent.ocr-base-url:http://localhost:8773}") String baseUrl,
            @Value("${nubian.agent.ocr-enabled:false}") boolean enabled,
            @Value("${nubian.agent.ocr-crop-w:300}") int cropW,
            @Value("${nubian.agent.ocr-crop-h:100}") int cropH,
            @Value("${nubian.agent.ocr-connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${nubian.agent.ocr-read-timeout-ms:8000}") int readTimeoutMs) {
        this.baseUrl = trimSlash(baseUrl);
        this.enabled = enabled;
        this.cropW = Math.max(64, cropW);
        this.cropH = Math.max(32, cropH);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        log.info("[ocr] base={} enabled={} cropW={} cropH={}", this.baseUrl, this.enabled, this.cropW, this.cropH);
    }

    public boolean enabled() { return enabled; }
    public int cropW() { return cropW; }
    public int cropH() { return cropH; }
    public String baseUrl() { return baseUrl; }

    public record OcrBox(int number, String text, double confidence,
                         int absX, int absY, int absW, int absH) {
        public int centerX() { return absX + absW / 2; }
        public int centerY() { return absY + absH / 2; }
    }

    public record OcrResult(byte[] annotatedCropPng, List<OcrBox> boxes,
                            int cropX, int cropY, int cropW, int cropH,
                            String rawJson) {
        public boolean isEmpty() { return boxes == null || boxes.isEmpty(); }
    }

    /**
     * OCRs the FULL screenshot and returns every detected text region in absolute pixels.
     * Use this for {@code find_and_click} / {@code menu_path} — they need to locate text
     * anywhere on the screen, not just near a guessed click coordinate.
     */
    public Optional<List<OcrBox>> ocrFull(byte[] screenshot) {
        if (!enabled) return Optional.empty();
        if (screenshot == null || screenshot.length == 0) return Optional.empty();
        try {
            String json = postOcr(screenshot);
            if (json == null || json.isBlank()) return Optional.empty();
            List<OcrBox> boxes = parseBoxes(json, 0, 0);
            log.debug("[ocr.full] {} boxes detected", boxes.size());
            return Optional.of(boxes);
        } catch (Exception ex) {
            log.warn("[ocr.full] failed: {}", ex.toString());
            return Optional.empty();
        }
    }

    /**
     * Finds a text target on a full screenshot.
     * Match priority: exact (case-insensitive) → starts-with → contains → token-overlap.
     * If multiple boxes match equally, the one closest to (preferX, preferY) wins; if no preference,
     * the first match (top-down, left-right reading order) wins. Returns the matching box (in absolute
     * coords, with its position in the full-screen list as the {@code number}) plus the full list so
     * callers can tell the model what alternatives exist.
     */
    public LocateResult locate(byte[] screenshot, String wanted, Integer preferX, Integer preferY) {
        Optional<List<OcrBox>> r = ocrFull(screenshot);
        if (r.isEmpty()) return LocateResult.failed("OCR returned no detections (service down or empty page).", List.of());
        List<OcrBox> all = r.get();
        if (all.isEmpty()) return LocateResult.failed("OCR found no text on the screen.", List.of());
        String want = wanted == null ? "" : wanted.trim();
        if (want.isEmpty()) return LocateResult.failed("locate: empty target text.", all);
        String wantLow = want.toLowerCase();

        // Strip trailing menu-marker characters from comparison so a command with ellipsis matches
        // an OCR'd command without the marker. User-supplied ellipses/arrows shouldn't
        // make exact matches fail.
        String wantCanon = canonLabel(wantLow);

        List<OcrBox> exact = new ArrayList<>();
        List<OcrBox> exactNorm = new ArrayList<>();   // exact after stripping menu markers
        List<OcrBox> startsList = new ArrayList<>();
        List<OcrBox> wordPrefix = new ArrayList<>();  // matches as a word at start of label
        List<OcrBox> contains = new ArrayList<>();
        for (OcrBox b : all) {
            String low = b.text().toLowerCase();
            String canon = canonLabel(low);
            if (low.equals(wantLow) || canon.equals(wantCanon)) {
                exact.add(b);
            } else if (low.equals(wantCanon) || canon.equals(wantLow)) {
                exactNorm.add(b);
            } else if (low.startsWith(wantLow) || canon.startsWith(wantCanon)) {
                startsList.add(b);
            } else if (isWordPrefix(low, wantLow) || isWordPrefix(canon, wantCanon)) {
                wordPrefix.add(b);
            } else if (low.contains(wantLow) || canon.contains(wantCanon)) {
                contains.add(b);
            }
        }
        List<OcrBox> tier;
        String tierName;
        if (!exact.isEmpty())            { tier = exact;       tierName = "exact"; }
        else if (!exactNorm.isEmpty())   { tier = exactNorm;   tierName = "exact-canon"; }
        else if (!startsList.isEmpty())  { tier = startsList;  tierName = "prefix"; }
        else if (!wordPrefix.isEmpty())  { tier = wordPrefix;  tierName = "word-prefix"; }
        else if (!contains.isEmpty())    { tier = contains;    tierName = "substring"; }
        else {
            return LocateResult.failed("No OCR box matches '" + want + "'. Detected texts: "
                    + previewTexts(all, 30), all);
        }
        // Within a tier, prefer the SHORTEST label (closest to the user's exact request).
        // This makes a shorter exact command label win over a longer label that contains it.
        OcrBox best = tier.get(0);
        int bestLen = best.text().length();
        for (OcrBox b : tier) {
            int len = b.text().length();
            if (len < bestLen) { best = b; bestLen = len; }
        }
        // Locality preference (preferX/preferY) overrides length ranking only inside the same tier
        // and only when multiple candidates remain after length filtering.
        if (preferX != null && preferY != null && tier.size() > 1) {
            List<OcrBox> shortest = new ArrayList<>();
            for (OcrBox b : tier) if (b.text().length() == bestLen) shortest.add(b);
            if (shortest.size() > 1) {
                double bestDist = dist(shortest.get(0), preferX, preferY);
                best = shortest.get(0);
                for (OcrBox b : shortest) {
                    double d = dist(b, preferX, preferY);
                    if (d < bestDist) { best = b; bestDist = d; }
                }
            }
        }
        log.debug("[ocr.locate] '{}' -> '{}' tier={} (of {} candidates)",
                want, best.text(), tierName, tier.size());
        return LocateResult.found(best, all, tier.size());
    }

    /** Strips trailing menu markers ("...", " ►", " >", " ▶", "…", trailing whitespace). */
    private static String canonLabel(String s) {
        if (s == null) return "";
        String out = s.trim();
        // Trailing menu markers
        out = out.replaceAll("\\s*[…>►▶▸→]+\\s*$", "");
        out = out.replaceAll("\\.{2,}\\s*$", "");
        // Strip parenthesised hotkey hints like "(Ctrl+G)"
        out = out.replaceAll("\\s*\\([^)]{1,12}\\)\\s*$", "");
        return out.trim();
    }

    /** True if {@code want} appears as a whole-word prefix of {@code label} ("gaussian" → "gaussian blur"). */
    private static boolean isWordPrefix(String label, String want) {
        if (label == null || want == null || want.isBlank()) return false;
        if (!label.startsWith(want)) return false;
        if (label.length() == want.length()) return true;
        char next = label.charAt(want.length());
        return !Character.isLetterOrDigit(next);
    }

    public record LocateResult(OcrBox match, List<OcrBox> allBoxes, int matchCount, String error) {
        public boolean ok() { return match != null; }
        public static LocateResult found(OcrBox m, List<OcrBox> all, int count) {
            return new LocateResult(m, all, count, null);
        }
        public static LocateResult failed(String error, List<OcrBox> all) {
            return new LocateResult(null, all, 0, error);
        }
    }

    private static double dist(OcrBox b, int x, int y) {
        long dx = b.centerX() - x;
        long dy = b.centerY() - y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static String previewTexts(List<OcrBox> boxes, int max) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(max, boxes.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append("'").append(boxes.get(i).text()).append("'");
        }
        if (boxes.size() > max) sb.append(", … ").append(boxes.size() - max).append(" more");
        sb.append("]");
        return sb.toString();
    }

    /** Crops a {@link #cropW}×{@link #cropH} window centered on (origX, origY), OCRs it, returns numbered boxes. */
    public Optional<OcrResult> annotate(byte[] screenshot, int origX, int origY) {
        if (!enabled) return Optional.empty();
        if (screenshot == null || screenshot.length == 0) return Optional.empty();
        if (origX < 0 || origY < 0) return Optional.empty();
        try {
            BufferedImage full = ImageIO.read(new ByteArrayInputStream(screenshot));
            if (full == null) return Optional.empty();
            int cropX = clamp(origX - cropW / 2, 0, Math.max(0, full.getWidth() - 1));
            int cropY = clamp(origY - cropH / 2, 0, Math.max(0, full.getHeight() - 1));
            int actualW = Math.min(full.getWidth() - cropX, cropW);
            int actualH = Math.min(full.getHeight() - cropY, cropH);
            if (actualW <= 0 || actualH <= 0) return Optional.empty();
            BufferedImage crop = full.getSubimage(cropX, cropY, actualW, actualH);
            byte[] cropPng = toPng(crop);

            String json = postOcr(cropPng);
            if (json == null || json.isBlank()) return Optional.empty();

            List<OcrBox> boxes = parseBoxes(json, cropX, cropY);
            byte[] annotated = annotateCrop(crop, boxes, cropX, cropY);
            log.debug("[ocr] crop={}x{}@({},{}) -> {} boxes", actualW, actualH, cropX, cropY, boxes.size());
            return Optional.of(new OcrResult(annotated, boxes, cropX, cropY, actualW, actualH, json));
        } catch (Exception ex) {
            log.warn("[ocr] annotate failed at ({},{}): {}", origX, origY, ex.toString());
            return Optional.empty();
        }
    }

    private List<OcrBox> parseBoxes(String json, int cropX, int cropY) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        // PP-OCRv5 plain shim shape: {results:[{res:{rec_texts, rec_scores, rec_boxes, rec_polys}}]}
        List<OcrBox> ppOcr = parsePaddleColumnar(root, cropX, cropY);
        if (!ppOcr.isEmpty()) return ppOcr;
        // Generic row-oriented fallback: array of {text, box|bbox|polygon, score|confidence}
        JsonNode arr = pickArray(root);
        List<OcrBox> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        int num = 1;
        for (JsonNode det : arr) {
            String text = firstNonBlank(
                    det.path("text").asText(""),
                    det.path("transcription").asText(""),
                    det.path("ocr_text").asText(""));
            if (text.isBlank()) continue;
            double conf = det.path("score").asDouble(
                    det.path("confidence").asDouble(
                            det.path("conf").asDouble(0.0)));
            int[] bbox = readBbox(det);
            if (bbox == null) continue;
            int absX = bbox[0] + cropX;
            int absY = bbox[1] + cropY;
            out.add(new OcrBox(num++, text.trim(), conf, absX, absY, bbox[2], bbox[3]));
        }
        return out;
    }

    /**
     * Parses PP-OCRv5 plain-shim response shape:
     * {"results":[{"res":{"rec_texts":[...],"rec_scores":[...],"rec_boxes":[[x1,y1,x2,y2],...],
     *  "rec_polys":[[[x,y]x4],...]}}]}
     * rec_boxes are [x1,y1,x2,y2] CORNERS, not [x,y,w,h].
     */
    private List<OcrBox> parsePaddleColumnar(JsonNode root, int cropX, int cropY) {
        List<OcrBox> out = new ArrayList<>();
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) return out;
        int num = 1;
        for (JsonNode page : results) {
            JsonNode res = page.path("res");
            JsonNode texts = res.path("rec_texts");
            JsonNode scores = res.path("rec_scores");
            JsonNode boxes = res.path("rec_boxes");
            JsonNode polys = res.path("rec_polys");
            if (!texts.isArray() || texts.isEmpty()) continue;
            int n = texts.size();
            for (int i = 0; i < n; i++) {
                String text = texts.get(i).asText("");
                if (text == null || text.isBlank()) continue;
                double conf = (scores.isArray() && i < scores.size()) ? scores.get(i).asDouble(0.0) : 0.0;
                int[] xywh = paddleBoxAt(boxes, polys, i);
                if (xywh == null) continue;
                out.add(new OcrBox(num++, text.trim(), conf,
                        xywh[0] + cropX, xywh[1] + cropY, xywh[2], xywh[3]));
            }
        }
        return out;
    }

    /** Reads boxes[i] (=[x1,y1,x2,y2]) or polys[i] (=4 corners) and returns [x,y,w,h]. */
    private static int[] paddleBoxAt(JsonNode boxes, JsonNode polys, int i) {
        if (boxes != null && boxes.isArray() && i < boxes.size()) {
            JsonNode b = boxes.get(i);
            if (b.isArray() && b.size() >= 4) {
                int x1 = b.get(0).asInt();
                int y1 = b.get(1).asInt();
                int x2 = b.get(2).asInt();
                int y2 = b.get(3).asInt();
                int w = Math.max(1, x2 - x1);
                int h = Math.max(1, y2 - y1);
                return new int[]{x1, y1, w, h};
            }
        }
        if (polys != null && polys.isArray() && i < polys.size()) {
            JsonNode poly = polys.get(i);
            if (poly.isArray() && poly.size() >= 2) {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
                for (JsonNode p : poly) {
                    if (!p.isArray() || p.size() < 2) continue;
                    int x = p.get(0).asInt();
                    int y = p.get(1).asInt();
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
                if (minX == Integer.MAX_VALUE) return null;
                return new int[]{minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY)};
            }
        }
        return null;
    }

    private static JsonNode pickArray(JsonNode root) {
        if (root.isArray()) return root;
        for (String key : List.of("results", "result", "data", "detections", "ocr", "boxes", "ocr_results")) {
            JsonNode n = root.path(key);
            if (n.isArray()) return n;
            if (n.isObject()) {
                for (String inner : List.of("results", "data", "detections", "boxes")) {
                    JsonNode m = n.path(inner);
                    if (m.isArray()) return m;
                }
            }
        }
        return null;
    }

    /**
     * Tolerant bbox reader.
     * Supports:
     *   {"box":[[x1,y1],[x2,y2],[x3,y3],[x4,y4]]}  ← PaddleOCR polygon
     *   {"polygon":[...]} / {"points":[...]} / {"poly":[...]}
     *   {"bbox":[x,y,w,h]}
     *   {"bbox":[x1,y1,x2,y2]}  (heuristic: x2 > x1 + 10 → treat as corners)
     *   {"x":..,"y":..,"width":..,"height":..}
     */
    private static int[] readBbox(JsonNode det) {
        JsonNode polygon = firstArray(det, "box", "polygon", "points", "poly");
        if (polygon != null && polygon.size() >= 2 && polygon.get(0).isArray()) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (JsonNode p : polygon) {
                if (!p.isArray() || p.size() < 2) continue;
                int x = p.get(0).asInt();
                int y = p.get(1).asInt();
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
            if (minX == Integer.MAX_VALUE) return null;
            return new int[]{minX, minY, Math.max(1, maxX - minX), Math.max(1, maxY - minY)};
        }
        JsonNode rect = firstArray(det, "bbox", "rect", "rectangle", "box");
        if (rect != null && rect.size() >= 4 && rect.get(0).isNumber()) {
            int a = rect.get(0).asInt();
            int b = rect.get(1).asInt();
            int c = rect.get(2).asInt();
            int d = rect.get(3).asInt();
            // Heuristic: if c looks like a far-right corner (c > a) and d > b, treat as [x1,y1,x2,y2]
            if (c > a && d > b && (c - a) < 5000 && (d - b) < 5000) {
                int width = c - a;
                int height = d - b;
                // Only treat as corners if both deltas are reasonable widths/heights
                if (c >= a + 4 && d >= b + 4) {
                    return new int[]{a, b, Math.max(1, width), Math.max(1, height)};
                }
            }
            if (c > 0 && d > 0) {
                return new int[]{a, b, c, d};
            }
        }
        if (det.has("x") && det.has("y") && (det.has("w") || det.has("width"))) {
            int x = det.path("x").asInt();
            int y = det.path("y").asInt();
            int w = det.has("w") ? det.path("w").asInt() : det.path("width").asInt();
            int h = det.has("h") ? det.path("h").asInt() : det.path("height").asInt();
            if (w > 0 && h > 0) return new int[]{x, y, w, h};
        }
        return null;
    }

    private static JsonNode firstArray(JsonNode det, String... keys) {
        for (String k : keys) {
            JsonNode v = det.path(k);
            if (v.isArray()) return v;
        }
        return null;
    }

    private byte[] annotateCrop(BufferedImage crop, List<OcrBox> boxes, int cropX, int cropY) throws IOException {
        BufferedImage out = new BufferedImage(crop.getWidth(), crop.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(crop, 0, 0, null);
            g.setStroke(new BasicStroke(2f));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            for (OcrBox box : boxes) {
                int rx = box.absX() - cropX;
                int ry = box.absY() - cropY;
                g.setColor(new Color(0, 220, 0, 220));
                g.drawRect(rx, ry, box.absW(), box.absH());
                String label = String.valueOf(box.number());
                int labelW = label.length() <= 1 ? 22 : 28;
                int labelX = Math.max(0, rx);
                int labelY = Math.max(15, ry - 1);
                g.setColor(new Color(255, 255, 0, 235));
                g.fillRect(labelX, labelY - 14, labelW, 17);
                g.setColor(Color.BLACK);
                g.drawString(label, labelX + 4, labelY);
            }
        } finally {
            g.dispose();
        }
        return toPng(out);
    }

    private String postOcr(byte[] cropPng) {
        if (baseUrl.isEmpty()) {
            log.warn("[ocr] baseUrl empty — skipping POST");
            return null;
        }
        String boundary = "------NubianOcr" + System.nanoTime();
        String url = baseUrl + "/ocr";
        HttpURLConnection conn = null;
        long t0 = System.currentTimeMillis();
        try {
            URL u = URI.create(url).toURL();
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"crop.png\"\r\n");
                dos.writeBytes("Content-Type: image/png\r\n\r\n");
                dos.write(cropPng);
                dos.writeBytes("\r\n--" + boundary + "--\r\n");
            }
            int status = conn.getResponseCode();
            byte[] body = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String respText = new String(body, StandardCharsets.UTF_8);
            long dt = System.currentTimeMillis() - t0;
            if (status >= 400) {
                log.warn("[ocr] HTTP {} from {} in {}ms: {}", status, url, dt, truncate(respText, 240));
                return null;
            }
            log.debug("[ocr] POST {} -> {} in {}ms ({} bytes)", url, status, dt, body.length);
            return respText;
        } catch (Exception ex) {
            log.warn("[ocr] post failed to {}: {}", url, ex.toString());
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignore) { /* noop */ }
        }
    }

    /** Render boxes as compact JSON, suitable to paste into the model's observation. */
    public static String boxesAsJson(List<OcrBox> boxes) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (OcrBox b : boxes) {
            ObjectNode n = arr.addObject();
            n.put("box", b.number());
            n.put("text", b.text());
            n.put("center_x", b.centerX());
            n.put("center_y", b.centerY());
            n.put("x", b.absX());
            n.put("y", b.absY());
            n.put("w", b.absW());
            n.put("h", b.absH());
            if (b.confidence() > 0) n.put("conf", Math.round(b.confidence() * 100.0) / 100.0);
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        } catch (Exception ex) {
            return arr.toString();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try (InputStream s = in) { return s.readAllBytes(); }
    }

    private static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static String trimSlash(String s) {
        if (s == null || s.isBlank()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
