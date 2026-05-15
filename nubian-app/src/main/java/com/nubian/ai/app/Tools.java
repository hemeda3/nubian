package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component("appTools")
public final class Tools {

    private static final Logger log = LoggerFactory.getLogger(Tools.class);

    private final Sandbox sandbox;
    private final OcrAssist ocr;
    private final TargetSeeker seeker;
    private final OmniParserClient omniParser;

    private volatile OcrAssist.OcrResult lastOcrResult;
    private volatile OmniParserClient.ParseResult lastParse;
    private static final ThreadLocal<Boolean> RAW_SCREENSHOTS = new ThreadLocal<>();

    private static final Path TRACE_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "nubian-trace");
    private static final AtomicInteger TRACE_SEQ = new AtomicInteger(0);
    private static final int SCREEN_DELTA_GRID = 64;
    private static final int SCREEN_DELTA_RGB_THRESHOLD = 48;
    private static final double VISIBLE_CHANGE_THRESHOLD = 0.01;

    @Value("${nubian.tools.click.hover-delay-ms:120}")
    private int clickHoverDelayMs = 120;

    @Value("${nubian.tools.click.post-delay-ms:80}")
    private int clickPostDelayMs = 80;

    @Value("${nubian.tools.click.hold-ms:250}")
    private int clickHoldMs = 250;

    @Value("${nubian.tools.click.long-press-enabled:true}")
    private boolean clickLongPressEnabled = true;

    @Value("${nubian.tools.screenshot.retry-attempts:5}")
    private int screenshotRetryAttempts = 5;

    @Value("${nubian.tools.screenshot.retry-delay-ms:150}")
    private int screenshotRetryDelayMs = 150;

    @Autowired
    public Tools(Sandbox sandbox, Prompts prompts, OcrAssist ocr, TargetSeeker seeker,
            OmniParserClient omniParser) {
        this.sandbox = sandbox;
        this.ocr = ocr;
        this.seeker = seeker;
        this.omniParser = omniParser;
        try {
            Files.createDirectories(TRACE_DIR);
            log.info("[trace] writing screen captures to {}", TRACE_DIR);
        } catch (Exception ex) {
            log.warn("[trace] could not create trace dir {}: {}", TRACE_DIR, ex.toString());
        }
    }

    public Tools(Sandbox sandbox, Prompts prompts, OcrAssist ocr) {
        this(sandbox, prompts, ocr, null, null);
    }

    public OcrAssist.OcrResult lastOcrResult() { return lastOcrResult; }

    public OmniParserClient.ParseResult lastParse() { return lastParse; }

    public boolean omniParserEnabled() {
        return omniParser != null && omniParser.enabled();
    }

    public boolean omniParserHealthy() {
        return omniParser != null && omniParser.healthCheck();
    }

    public String omniParserBaseUrl() {
        return omniParser == null ? "" : omniParser.baseUrl();
    }

    byte[] captureRawScreenshot() {
        long t0 = System.currentTimeMillis();
        byte[] raw = captureSandboxScreenshot("raw");
        this.lastParse = null;
        log.info("[screenshot.raw] sandbox={}ms · {} bytes",
                System.currentTimeMillis() - t0, raw == null ? 0 : raw.length);
        return raw;
    }

    OmniParserClient.ParseResult parseRawScreenshot(byte[] raw) {
        if (omniParser == null || !omniParser.enabled() || raw == null || raw.length == 0) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        try {
            OmniParserClient.ParseResult pr = omniParser.parse(raw);
            this.lastParse = pr;
            log.info("[screenshot.raw.parse] parse={}ms · {} elements",
                    System.currentTimeMillis() - t0,
                    pr.elements() == null ? 0 : pr.elements().size());
            return pr;
        } catch (RuntimeException ex) {
            log.warn("[screenshot.raw.parse] failed_after={}ms · {}",
                    System.currentTimeMillis() - t0, ex.toString());
            return null;
        }
    }

    /** Verifier evidence bundle from the sandbox's /eyes/evidence endpoint:
     *  windows, processes, dir listings, file stats, recent files, clipboard.
     *  Returns the raw JSON string or empty string on failure. */
    String evidenceBundle(String dirsCsv, String filesCsv) {
        try {
            Sandbox.Response r = sandbox.evidenceBundle(dirsCsv, filesCsv);
            return r != null && r.body() != null ? r.body() : "";
        } catch (RuntimeException ex) {
            log.warn("[evidence] sandbox call failed: {}", ex.toString());
            return "";
        }
    }

    String clipboardText() {
        try {
            Sandbox.Response r = sandbox.clipboardText();
            return r != null && r.body() != null ? r.body() : "";
        } catch (RuntimeException ex) {
            return "{\"error\":\"" + ex.getMessage() + "\"}";
        }
    }

    String filesList(String path) {
        try {
            Sandbox.Response r = sandbox.filesList(path);
            return r != null && r.body() != null ? r.body() : "";
        } catch (RuntimeException ex) {
            return "{\"error\":\"" + ex.getMessage() + "\"}";
        }
    }

    String fileStat(String path) {
        try {
            Sandbox.Response r = sandbox.fileStat(path);
            return r != null && r.body() != null ? r.body() : "";
        } catch (RuntimeException ex) {
            return "{\"error\":\"" + ex.getMessage() + "\"}";
        }
    }

    String readFileText(String path) {
        try {
            Sandbox.Response r = sandbox.readFile(path);
            return r != null && r.body() != null ? r.body() : "";
        } catch (RuntimeException ex) {
            return "{\"error\":\"" + ex.getMessage() + "\"}";
        }
    }

    <T> T withRawScreenshots(Supplier<T> action) {
        Boolean prior = RAW_SCREENSHOTS.get();
        RAW_SCREENSHOTS.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (prior == null) RAW_SCREENSHOTS.remove();
            else RAW_SCREENSHOTS.set(prior);
        }
    }

    /** Capture a fresh sandbox screenshot. If OmniParser is wired and enabled, replace the bytes
     *  with the labeled PNG (numbered boxes burned in) and stash the element list so
     *  {@code click_box{id:N}} can resolve N -> bbox center. Falls back to the raw screenshot
     *  on any OmniParser failure so the agent never stalls on a remote outage. */
    private byte[] screenshotLabeled() {
        if (Boolean.TRUE.equals(RAW_SCREENSHOTS.get())) {
            return captureRawScreenshot();
        }
        long t0 = System.currentTimeMillis();
        byte[] raw = captureSandboxScreenshot("labeled");
        long tShot = System.currentTimeMillis() - t0;
        if (omniParser == null || !omniParser.enabled() || raw == null || raw.length == 0) {
            log.info("[screenshot] sandbox={}ms (omniparser disabled, raw {} bytes)",
                    tShot, raw == null ? 0 : raw.length);
            return raw;
        }
        long t1 = System.currentTimeMillis();
        try {
            OmniParserClient.ParseResult pr = omniParser.parse(raw);
            this.lastParse = pr;
            long tParse = System.currentTimeMillis() - t1;
            log.info("[screenshot] sandbox={}ms parse={}ms total={}ms · {} elements",
                    tShot, tParse, System.currentTimeMillis() - t0,
                    pr.elements() == null ? 0 : pr.elements().size());
            return (pr.labeledPng() != null && pr.labeledPng().length > 0) ? pr.labeledPng() : raw;
        } catch (RuntimeException ex) {
            log.warn("[screenshot] sandbox={}ms parse_failed_after={}ms · {} · falling back to raw",
                    tShot, System.currentTimeMillis() - t1, ex.toString());
            return raw;
        }
    }

    private byte[] captureSandboxScreenshot(String label) {
        int attempts = Math.max(1, screenshotRetryAttempts);
        byte[] last = null;
        for (int i = 1; i <= attempts; i++) {
            last = sandbox.screenshot();
            if (looksLikePng(last)) {
                if (i > 1) {
                    log.warn("[screenshot.{}] recovered valid PNG after {}/{} attempts ({} bytes)",
                            label, i, attempts, last.length);
                }
                return last;
            }
            log.warn("[screenshot.{}] invalid PNG attempt {}/{} ({} bytes)",
                    label, i, attempts, last == null ? 0 : last.length);
            if (i < attempts) sleepForScreenshotRetry();
        }
        throw new IllegalStateException("screenshot returned invalid PNG after "
                + attempts + " attempts (last bytes=" + (last == null ? 0 : last.length) + ")");
    }

    private void sleepForScreenshotRetry() {
        int ms = Math.max(0, screenshotRetryDelayMs);
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ix) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ix);
        }
    }

    private static void traceSave(String tag, byte[] png, Integer x, Integer y) {
        if (png == null || png.length == 0) return;
        if (!looksLikePng(png)) {
            log.warn("[trace] skipped invalid screenshot bytes for {} ({} bytes)",
                    tag == null ? "frame" : tag, png.length);
            return;
        }
        try {
            int seq = TRACE_SEQ.incrementAndGet();
            long ts = System.currentTimeMillis();
            String safe = sanitize(tag == null ? "frame" : tag);
            if (safe.length() > 60) safe = safe.substring(0, 60);
            String name = String.format("%05d_%013d_%s.png", seq, ts, safe);
            Path path = TRACE_DIR.resolve(name);
            if (x != null && y != null) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                if (img != null) {
                    Graphics2D g = img.createGraphics();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(new Color(255, 0, 0, 230));
                        g.setStroke(new BasicStroke(3f));
                        int radius = 18;
                        g.drawOval(x - radius, y - radius, radius * 2, radius * 2);
                        g.drawLine(x - radius * 2, y, x + radius * 2, y);
                        g.drawLine(x, y - radius * 2, x, y + radius * 2);
                        g.setColor(new Color(255, 255, 0, 255));
                        g.fillOval(x - 3, y - 3, 6, 6);
                    } finally {
                        g.dispose();
                    }
                    ImageIO.write(img, "png", path.toFile());
                    return;
                }
            }
            Files.write(path, png);
        } catch (Exception ex) {
            log.debug("[trace] write failed: {}", ex.toString());
        }
    }

    private static void tracePair(String tag, byte[] before, byte[] after, Integer x, Integer y) {
        String base = tag == null || tag.isBlank() ? "action" : tag;
        traceSave(base + "_before", before, x, y);
        traceSave(base + "_after", after, x, y);
    }

    private static boolean looksLikePng(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return false;
        return (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G'
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a;
    }

    public record Outcome(boolean ok, boolean done, String summary,
            byte[] screenshotPng, byte[] ocrCropPng, String ocrBoxesJson,
            Map<String, String> metadata) {
        public Outcome {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public static Outcome ok(String summary, byte[] shot) {
            return new Outcome(true, false, summary, shot, null, null, Map.of());
        }

        public static Outcome ok(String summary, byte[] shot, Map<String, String> meta) {
            return new Outcome(true, false, summary, shot, null, null, meta);
        }

        public static Outcome done(String summary) {
            return new Outcome(true, true, summary, null, null, null, Map.of());
        }

        public static Outcome fail(String summary) {
            return new Outcome(false, false, summary, null, null, null, Map.of());
        }

        public static Outcome fail(String summary, Map<String, String> meta) {
            return new Outcome(false, false, summary, null, null, null, meta);
        }

        public static Outcome fail(String summary, byte[] shot, Map<String, String> meta) {
            return new Outcome(false, false, summary, shot, null, null, meta);
        }

        public Outcome withOcr(byte[] cropPng, String boxesJson) {
            return new Outcome(ok, done, summary, screenshotPng, cropPng, boxesJson, metadata);
        }
    }

    public Outcome invoke(String toolName, JsonNode args) {
        if (toolName == null || toolName.isBlank()) {
            return Outcome.fail("error: empty tool name");
        }
        try {
            return switch (toolName) {
                case "click_target" -> clickTarget(args);
                case "click" -> click(args, "left", 1);
                case "double_click" -> click(args, "left", 2);
                case "right_click" -> click(args, "right", 1);
                case "click_box" -> clickBox(args);
                case "find_click", "find_and_click", "click_text" -> findClick(args);
                case "menu_path", "menu_walk" -> menuPath(args);
                case "compound_click", "click_path" -> compoundClick(args);
                case "drag", "drag_to", "drag_between", "drag_from_to", "drag_drop" -> drag(args);
                case "drag_box", "left_click_drag" -> dragBox(args);
                case "type_text", "type" -> typeText(args);
                case "hotkey", "key", "press_key" -> hotkey(args);
                case "enter" -> hotkey(List.of("enter"));
                case "escape" -> hotkey(List.of("escape"));
                case "scroll" -> scroll(args);
                case "modified_scroll", "chord_scroll" -> modifiedScroll(args);
                case "key_down", "keydown" -> keyDown(args);
                case "key_up", "keyup" -> keyUp(args);
                case "long_click", "click_hold", "hold_click" -> longClick(args);
                case "modified_click", "chord_click" -> modifiedClick(args);
                case "modified_drag", "chord_drag" -> modifiedDrag(args);
                case "mouse_path", "freehand_path", "lasso_path" -> mousePath(args);
                case "scrub_slider", "slider_scrub" -> scrubSlider(args);
                case "drag_hold_observe_release", "drag_probe_release" -> dragHoldObserveRelease(args);
                case "nudge", "press_key_repeat", "key_repeat" -> nudge(args);
                case "wait" -> waitTool(args);
                case "launch_app" -> launchApp(args);
                case "activate_window", "window_activate", "activate_app", "focus_window", "focus_app" -> activateWindow(args);
                case "close_window", "window_close", "close_app", "quit_app" -> closeWindow(args);
                case "list_apps", "apps_catalog" -> listApps(args);
                case "write_file" -> writeFile(args);
                case "read_file" -> readFile(args);
                case "done" -> done(args);
                case "fail" -> Outcome.fail(requiredText(args, "reason"));
                case "screenshot" -> screenshot();
                case "python", "shell" -> Outcome.fail("error: '" + toolName
                        + "' is disabled. Use only the contract tools: screenshot, launch_app, click, click_box, "
                        + "list_apps, find_click, menu_path, compound_click, type, hotkey, scroll, wait, activate_window, close_window, write_file, "
                        + "read_file, done, fail.");
                default -> Outcome.fail("error: unknown tool '" + toolName + "'. Allowed tools: "
                        + "screenshot, launch_app, list_apps, click, click_box, find_click, menu_path, compound_click, "
                        + "type, hotkey, scroll, wait, activate_window, close_window, write_file, read_file, done, fail.");
            };
        } catch (IllegalArgumentException ex) {
            return Outcome.fail("error: " + ex.getMessage());
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            log.error("[tool.{}] threw: {}", toolName, ex.toString(), ex);
            return Outcome.fail("error: " + toolName + " threw: " + ex.getMessage());
        }
    }

    private Outcome clickTarget(JsonNode args) {
        if (seeker == null || !seeker.enabled()) {
            return Outcome.fail("click_target unavailable — seeker disabled. Use click{x,y} instead.");
        }
        String target = requiredText(args, "target");
        Integer initX = args.has("x") ? args.path("x").asInt() : null;
        Integer initY = args.has("y") ? args.path("y").asInt() : null;
        TargetSeeker.SeekResult r = seeker.seekAndClick(target, initX, initY);
        byte[] post = screenshotLabeled();
        traceSave("click_target_" + r.x() + "_" + r.y(), post, r.x(), r.y());
        String summary = "click_target '" + target + "' " + (r.hit() ? "HIT" : "GAVE UP")
                + " after " + r.attempts() + " attempt(s) at (" + r.x() + ", " + r.y() + ") — " + r.summary();
        return r.hit() ? Outcome.ok(summary, post) : Outcome.fail(summary);
    }

    private Outcome click(JsonNode args, String button, int clicks) {
        int x = requiredInt(args, "x");
        int y = requiredInt(args, "y");
        byte[] pre = captureActionBaseline();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("x", x);
        body.put("y", y);
        body.put("button", text(args, "button", button));
        if (clicks > 1) body.put("clicks", clicks);
        Sandbox.Response r = humanizedClick(body, x, y);
        String summary = "click " + button + " at (" + x + ", " + y + ")" + (clicks > 1 ? " x" + clicks : "");
        if (!r.ok()) {
            return Outcome.fail(summary + " failed HTTP " + r.status() + ": " + truncate(r.body(), 400));
        }
        byte[] post = screenshotLabeled();
        tracePair("click_" + button + (clicks > 1 ? "x" + clicks : "") + "_" + x + "_" + y,
                pre, post, x, y);
        return attachOcr(visibleActionOutcome(summary, post, screenDelta(pre, post), true), x, y);
    }

    private Outcome clickBox(JsonNode args) {
        int boxId = args.has("id") ? args.get("id").asInt() : requiredInt(args, "box");
        OmniParserClient.ParseResult parse = lastParse;
        if (parse == null || parse.elements() == null || parse.elements().isEmpty()) {
            return Outcome.fail("click_box: no parsed elements available yet — call screenshot first so OmniParser can label the screen.");
        }
        if (boxId < 0 || boxId >= parse.elements().size()) {
            int max = parse.elements().size() - 1;
            return Outcome.fail("click_box: id " + boxId + " out of range [0.." + max + "] for current parse ("
                    + parse.elements().size() + " elements).");
        }
        OmniParserClient.Element e = parse.elements().get(boxId);
        int x = e.centerX();
        int y = e.centerY();
        byte[] pre = captureActionBaseline();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("x", x);
        body.put("y", y);
        body.put("button", "left");
        Sandbox.Response r = humanizedClick(body, x, y);
        String summary = "click_box id=" + boxId + " type=" + e.type()
                + " content='" + truncate(e.content(), 40) + "' at (" + x + ", " + y + ")";
        if (!r.ok()) {
            return Outcome.fail(summary + " failed HTTP " + r.status() + ": " + truncate(r.body(), 400));
        }
        byte[] post = screenshotLabeled();
        tracePair("click_box_" + boxId + "_" + x + "_" + y, pre, post, x, y);
        return Outcome.ok(summary, post);
    }

    private Outcome findClick(JsonNode args) {
        String text = requiredText(args, "text");
        boolean doubleClick = args.path("double").asBoolean(args.path("double_click").asBoolean(false));
        String button = text(args, "button", "left");
        Integer prefX = args.has("near_x") ? args.path("near_x").asInt() : null;
        Integer prefY = args.has("near_y") ? args.path("near_y").asInt() : null;
        int dx = args.path("dx").asInt(0);
        int dy = args.path("dy").asInt(0);
        if (ocr == null || !ocr.enabled()) {
            return Outcome.fail("find_click: OCR disabled — use raw click(x,y) instead.");
        }
        byte[] preShot = sandbox.screenshot();
        OcrAssist.LocateResult loc = ocr.locate(preShot, text, prefX, prefY);
        if (!loc.ok()) {
            return Outcome.fail("find_click: " + loc.error());
        }
        OcrAssist.OcrBox box = loc.match();
        int x = box.centerX() + dx;
        int y = box.centerY() + dy;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("x", x);
        body.put("y", y);
        body.put("button", button);
        if (doubleClick) body.put("clicks", 2);
        Sandbox.Response r = humanizedClick(body, x, y);
        String label = (doubleClick ? "double_" : "") + button + "click '" + truncate(box.text(), 40) + "'";
        String summary = "find_click " + label + " at (" + x + ", " + y + ")"
                + (loc.matchCount() > 1 ? " [" + loc.matchCount() + " candidates, picked " + (prefX != null ? "nearest" : "first") + "]" : "");
        if (!r.ok()) {
            return Outcome.fail(summary + " failed HTTP " + r.status() + ": " + truncate(r.body(), 400));
        }
        byte[] post = screenshotLabeled();
        tracePair("find_click_" + sanitize(box.text()) + "_" + x + "_" + y, preShot, post, x, y);
        Outcome ok = Outcome.ok(summary, post);
        return attachOcr(ok, x, y);
    }

    private Outcome menuPath(JsonNode args) {
        JsonNode path = args.path("path");
        if (!path.isArray() || path.isEmpty()) {
            // Fallback: legacy compound_click coordinate path
            if (path.isArray() && !path.isEmpty() && path.get(0).isObject() && path.get(0).has("x")) {
                return compoundClick(args);
            }
            throw new IllegalArgumentException(
                    "menu_path requires non-empty 'path' array of strings, e.g. [\"File\",\"Open\"]");
        }
        if (ocr == null || !ocr.enabled()) {
            return Outcome.fail("menu_path: OCR disabled — cannot resolve menu items by text.");
        }
        // If path entries are coordinate objects {x,y}, delegate to legacy compound_click.
        if (path.get(0).isObject() && path.get(0).has("x")) return compoundClick(args);

        int delayMs = Math.max(50, args.path("delay_ms").asInt(180));
        int settleMs = Math.max(0, args.path("settle_ms").asInt(150));
        byte[] pre = captureActionBaseline();
        // Always start from a clean state: escape any half-open menu or lingering submenu from a
        // prior failed walk. Some apps put dynamic recent-command entries near the top of menus;
        // dismissing first helps the OCR-driven walk start from the canonical menu structure.
        sandbox.hands("hotkey", Map.of("keys", List.of("escape")));
        sandbox.hands("hotkey", Map.of("keys", List.of("escape")));

        StringBuilder summary = new StringBuilder("menu_path");
        int last_x = -1, last_y = -1;
        for (int i = 0; i < path.size(); i++) {
            String label = path.get(i).asText("");
            if (label == null || label.isBlank()) {
                return Outcome.fail(summary + " — empty label at step " + (i + 1));
            }
            byte[] shot = sandbox.screenshot();
            // Restrict ranking to the region where the next menu level is expected:
            //  - step 0 (top-level menu): top of screen, y < 130
            //  - step 1+ (submenu): below the previous click's y, within ~600px
            // This stops "Repeat <recent filter>" entries near the menu top from outranking real
            // submenu items further down once a submenu has actually opened.
            OcrAssist.LocateResult loc = ocr.locate(shot, label, null, null);
            if (loc.ok() && i > 0) {
                // For sub-menu steps, prefer matches that lie inside a vertical band starting just
                // below the previous step's click. If the best box is way above (still in the
                // top-level menu region), it's almost certainly a "Repeat X" pollution match —
                // refilter to ranking among the in-band candidates.
                OcrAssist.OcrBox refined = preferBelow(loc, last_y);
                if (refined != null && refined != loc.match()) {
                    log.info("[menu_path] step {} '{}' moved from ({},{}) to ({},{}) to escape top-menu pollution",
                            i + 1, label,
                            loc.match().centerX(), loc.match().centerY(),
                            refined.centerX(), refined.centerY());
                    loc = OcrAssist.LocateResult.found(refined, loc.allBoxes(), loc.matchCount());
                }
            }
            if (!loc.ok()) {
                // Dismiss any half-open menu before bailing so the next attempt starts clean.
                sandbox.hands("hotkey", Map.of("keys", List.of("escape")));
                sandbox.hands("hotkey", Map.of("keys", List.of("escape")));
                return Outcome.fail(summary + " — could not find '" + label + "' at step " + (i + 1)
                        + " (" + loc.error() + ")");
            }
            OcrAssist.OcrBox box = loc.match();
            int x = box.centerX();
            int y = box.centerY();
            Sandbox.Response r = humanizedClick(Map.of("x", x, "y", y, "button", "left"), x, y);
            if (!r.ok()) {
                sandbox.hands("hotkey", Map.of("keys", List.of("escape")));
                return Outcome.fail(summary + " — click '" + label + "' failed HTTP " + r.status());
            }
            summary.append(i == 0 ? " " : " > ").append("'").append(box.text())
                    .append("'@(").append(x).append(",").append(y).append(")");
            last_x = x; last_y = y;
            if (i < path.size() - 1 && delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ix) {
                    Thread.currentThread().interrupt();
                    return Outcome.fail("menu_path interrupted at step " + (i + 1));
                }
            }
        }
        if (settleMs > 0) {
            try { Thread.sleep(settleMs); } catch (InterruptedException ix) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] post = screenshotLabeled();
        tracePair("menu_path_" + last_x + "_" + last_y, pre, post, last_x, last_y);
        return attachOcr(Outcome.ok(summary.toString(), post), last_x, last_y);
    }

    /**
     * For sub-menu steps: if the locate result's best match is at or above {@code prevY},
     * search the all-boxes list for a candidate that (a) matches the same tier (we re-use the
     * existing match's text as the canonical target) and (b) is positioned BELOW prevY.
     * Returns null if no better candidate exists.
     */
    private static OcrAssist.OcrBox preferBelow(OcrAssist.LocateResult loc, int prevY) {
        if (prevY <= 0) return loc.match();
        OcrAssist.OcrBox cur = loc.match();
        if (cur.centerY() > prevY + 10) return cur;
        // Walk all boxes for a same-text match below prevY. We compare on canonicalised text so
        // repeated command labels in two regions both qualify.
        String wantText = cur.text().toLowerCase().trim();
        OcrAssist.OcrBox best = null;
        int bestY = Integer.MAX_VALUE;
        for (OcrAssist.OcrBox b : loc.allBoxes()) {
            if (b.centerY() <= prevY + 10) continue;
            if (!b.text().toLowerCase().trim().equals(wantText)) continue;
            if (b.centerY() < bestY) { best = b; bestY = b.centerY(); }
        }
        return best != null ? best : cur;
    }

    private static String sanitize(String s) {
        if (s == null) return "x";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            out.append(isSafeTagChar(ch) ? ch : '_');
        }
        String text = out.length() > 40 ? out.substring(0, 40) : out.toString();
        return text.isBlank() ? "x" : text;
    }

    private static boolean isSafeTagChar(char ch) {
        return (ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9')
                || ch == '_' || ch == '+' || ch == '-';
    }

    /**
     * After a click executes, run OCR on a small crop centered on the click and attach the
     * numbered annotated crop + boxes JSON to the outcome so the next iteration can see what
     * text was around the click and (if needed) refine via {@code click_box}.
     */
    private Outcome attachOcr(Outcome out, int x, int y) {
        if (ocr == null || !ocr.enabled()) return out;
        if (out.screenshotPng() == null || out.screenshotPng().length == 0) return out;
        Optional<OcrAssist.OcrResult> result = ocr.annotate(out.screenshotPng(), x, y);
        if (result.isEmpty()) return out;
        OcrAssist.OcrResult res = result.get();
        this.lastOcrResult = res;
        if (res.boxes().isEmpty()) return out;
        String boxesJson = OcrAssist.boxesAsJson(res.boxes());
        log.info("[ocr] {} boxes detected near ({},{}) — attached to outcome", res.boxes().size(), x, y);
        return out.withOcr(res.annotatedCropPng(), boxesJson);
    }

    private Outcome compoundClick(JsonNode args) {
        JsonNode path = args.path("path");
        if (!path.isArray() || path.isEmpty()) {
            throw new IllegalArgumentException(
                    "compound_click requires non-empty 'path' array of {x,y} objects");
        }
        int delayMs = Math.max(0, args.path("delay_ms").asInt(120));
        StringBuilder summary = new StringBuilder("compound_click ");
        summary.append(path.size()).append(" steps");
        byte[] pre = captureActionBaseline();
        int last_x = -1, last_y = -1;
        for (int i = 0; i < path.size(); i++) {
            JsonNode point = path.get(i);
            int x = point.path("x").asInt(-1);
            int y = point.path("y").asInt(-1);
            if (x < 0 || y < 0) {
                throw new IllegalArgumentException(
                        "compound_click path[" + i + "] must have integer x,y");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("x", x);
            body.put("y", y);
            body.put("button", "left");
            Sandbox.Response r = humanizedClick(body, x, y);
            if (!r.ok()) {
                return Outcome.fail(summary + " — failed at step " + (i + 1)
                        + " (" + x + "," + y + ") HTTP " + r.status() + ": " + truncate(r.body(), 200));
            }
            summary.append(i == 0 ? " (" : " -> (").append(x).append(",").append(y).append(")");
            last_x = x;
            last_y = y;
            if (i < path.size() - 1 && delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ix) {
                    Thread.currentThread().interrupt();
                    return Outcome.fail("compound_click interrupted at step " + (i + 1));
                }
            }
        }
        byte[] post = screenshotLabeled();
        tracePair("compound_click_" + last_x + "_" + last_y, pre, post, last_x, last_y);
        return attachOcr(Outcome.ok(summary.toString(), post), last_x, last_y);
    }

    private Outcome typeText(JsonNode args) {
        // mode:replace clears the focused field's content first, then types.
        //
        // History: an earlier sequence (F2 → End → Shift+Home → Delete) was
        // tuned for LibreOffice Calc cells, where F2 enters cell-edit mode
        // so subsequent navigation keys stay scoped to the cell's text. That
        // sequence catastrophically failed in browser forms: in Firefox, F2
        // can toggle caret-browsing (a confirmation dialog) or behave as a
        // no-op that doesn't move focus into a text-edit context — then the
        // End/Shift+Home/Delete keys operate on the wrong target and the
        // subsequent typed characters interleave with stale content (43 chars
        // typed, ~20 chars actually landed, garbled). Documented in the
        // LinkedIn-replace doom loop, 2026-05-15.
        //
        // New universal sequence: optional click → Ctrl+A (select-all-text)
        // → Delete → type. Works in browser inputs, Writer/Word text widgets,
        // IDE editors. Calc cells need a separate mode (see clear_strategy
        // arg) because Ctrl+A on the cell GRID selects every cell on the
        // sheet — destructive when followed by Delete. Callers targeting
        // spreadsheet cells must pass {"clear_strategy":"cell_edit"} to opt
        // into the F2-based sequence below.
        String text = firstText(args, "text", "text_to_type", "textToType", "value");
        if (text.isBlank()) throw new IllegalArgumentException("text is required");
        String mode = text(args, "mode", "append");
        String clearStrategy = text(args, "clear_strategy", "select_all").trim().toLowerCase();
        byte[] pre = captureActionBaseline();
        if ("replace".equalsIgnoreCase(mode)) {
            Integer x = optInt(args, "x");
            Integer y = optInt(args, "y");
            if (x != null && y != null) {
                Sandbox.Response focus = sandbox.hands("click",
                        Map.of("x", x, "y", y, "button", "left", "times", 1));
                if (!focus.ok()) return actionOutcome("type_text replace: focus click failed", focus, pre, false);
            }
            if ("cell_edit".equals(clearStrategy)) {
                // Calc-specific: F2 enters the cell editor so End/Shift+Home/
                // Delete stay scoped to the cell's text and don't reach the
                // cell-grid handler (which would otherwise treat Delete as
                // multi-cell range erase or trigger the "Delete Contents"
                // dialog under Backspace).
                Sandbox.Response edit = sandbox.hands("hotkey", Map.of("keys", List.of("f2")));
                if (!edit.ok()) return actionOutcome("type_text replace: F2 failed", edit, pre, false);
                Sandbox.Response end = sandbox.hands("hotkey", Map.of("keys", List.of("end")));
                if (!end.ok()) return actionOutcome("type_text replace: end failed", end, pre, false);
                Sandbox.Response sel = sandbox.hands("hotkey",
                        Map.of("keys", List.of("shift", "home")));
                if (!sel.ok()) return actionOutcome("type_text replace: shift+home failed", sel, pre, false);
                Sandbox.Response del = sandbox.hands("hotkey", Map.of("keys", List.of("delete")));
                if (!del.ok()) return actionOutcome("type_text replace: delete failed", del, pre, false);
            } else {
                // Default: universal browser/text-widget select-all sequence.
                Sandbox.Response sel = sandbox.hands("hotkey",
                        Map.of("keys", List.of("ctrl", "a")));
                if (!sel.ok()) return actionOutcome("type_text replace: ctrl+a failed", sel, pre, false);
                Sandbox.Response del = sandbox.hands("hotkey", Map.of("keys", List.of("delete")));
                if (!del.ok()) return actionOutcome("type_text replace: delete failed", del, pre, false);
            }
        }
        Sandbox.Response r = sandbox.hands("type", Map.of("text", text));
        return actionOutcome("typed " + text.length() + " chars", r, pre, true);
    }

    private static Integer optInt(JsonNode args, String key) {
        if (args == null || !args.has(key)) return null;
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.canConvertToInt()) return null;
        return n.asInt();
    }

    private Outcome hotkey(JsonNode args) {
        List<String> keys = keyList(args);
        if (keys.isEmpty()) throw new IllegalArgumentException("key requires 'key', 'combo', or 'keys'");
        return hotkey(keys);
    }

    private Outcome hotkey(List<String> keys) {
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("hotkey", Map.of("keys", keys));
        return actionOutcome("pressed " + String.join("+", keys), r, pre, true);
    }

    private Outcome scroll(JsonNode args) {
        // Simple contract: {"direction": "up|down|left|right", "amount": <int>}.
        // dy/dx legacy fields are accepted as a soft fallback (default to
        // "down" / "right" — never inferred from sign, which is unreliable).
        String direction = text(args, "direction", "").toLowerCase();
        if (direction.isBlank()) {
            int dx = args.path("dx").asInt(0);
            int dy = args.path("dy").asInt(0);
            direction = Math.abs(dx) > Math.abs(dy) ? "right" : "down";
        }
        int amount = Math.max(1, args.path("amount").asInt(5));
        if (!List.of("up", "down", "left", "right").contains(direction)) {
            throw new IllegalArgumentException("scroll direction must be up/down/left/right");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("direction", direction);
        body.put("amount", amount);
        if (args.has("x")) body.put("x", args.path("x").asInt());
        if (args.has("y")) body.put("y", args.path("y").asInt());
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("scroll", body);
        return actionOutcome("scrolled " + direction + " " + amount, r, pre, true);
    }

    private Outcome modifiedScroll(JsonNode args) {
        String direction = text(args, "direction", "down").toLowerCase();
        int amount = Math.max(1, args.path("amount").asInt(5));
        List<String> keys = parseKeyList(args, "keys");
        if (keys.isEmpty()) throw new IllegalArgumentException("modified_scroll requires keys[]");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("direction", direction); body.put("amount", amount); body.put("keys", keys);
        if (args.has("x")) body.put("x", args.path("x").asInt());
        if (args.has("y")) body.put("y", args.path("y").asInt());
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("modified_scroll", body);
        return actionOutcome(String.join("+", keys) + "+scroll " + direction + " " + amount, r, pre, true);
    }

    private Outcome keyDown(JsonNode args) {
        List<String> keys = parseKeyList(args, "keys", "key");
        if (keys.isEmpty()) throw new IllegalArgumentException("key_down requires keys[] or key");
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("key_down", Map.of("keys", keys));
        return actionOutcome("key_down " + String.join("+", keys), r, pre, true);
    }

    private Outcome keyUp(JsonNode args) {
        List<String> keys = parseKeyList(args, "keys", "key");
        if (keys.isEmpty()) throw new IllegalArgumentException("key_up requires keys[] or key");
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("key_up", Map.of("keys", keys));
        return actionOutcome("key_up " + String.join("+", keys), r, pre, true);
    }

    private Outcome longClick(JsonNode args) {
        Integer x = optInt(args, "x");
        Integer y = optInt(args, "y");
        if (x == null || y == null) throw new IllegalArgumentException("long_click requires x, y");
        int holdMs = Math.max(50, args.path("duration_ms").asInt(args.path("hold_ms").asInt(800)));
        String button = text(args, "button", "left");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("x", x); body.put("y", y);
        body.put("hold_seconds", holdMs / 1000.0);
        body.put("button", button);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("long_click", body);
        return actionOutcome("long_click " + button + " (" + x + "," + y + ") " + holdMs + "ms", r, pre, true);
    }

    private Outcome modifiedClick(JsonNode args) {
        Integer x = optInt(args, "x");
        Integer y = optInt(args, "y");
        if (x == null || y == null) throw new IllegalArgumentException("modified_click requires x, y");
        List<String> keys = parseKeyList(args, "keys");
        if (keys.isEmpty()) throw new IllegalArgumentException("modified_click requires keys[]");
        String button = text(args, "button", "left");
        int clicks = Math.max(1, args.path("clicks").asInt(1));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("x", x); body.put("y", y);
        body.put("keys", keys); body.put("button", button); body.put("clicks", clicks);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("modified_click", body);
        return actionOutcome(String.join("+", keys) + "+" + button + " click (" + x + "," + y + ")", r, pre, true);
    }

    private Outcome modifiedDrag(JsonNode args) {
        Integer fromX = optInt(args, "from_x");
        Integer fromY = optInt(args, "from_y");
        Integer toX = optInt(args, "to_x");
        Integer toY = optInt(args, "to_y");
        if (fromX == null || fromY == null || toX == null || toY == null) {
            throw new IllegalArgumentException("modified_drag requires from_x, from_y, to_x, to_y");
        }
        List<String> keys = parseKeyList(args, "keys");
        if (keys.isEmpty()) throw new IllegalArgumentException("modified_drag requires keys[]");
        double duration = args.path("duration").asDouble(0.4);
        String button = text(args, "button", "left");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_x", fromX); body.put("from_y", fromY);
        body.put("to_x", toX); body.put("to_y", toY);
        body.put("keys", keys); body.put("button", button); body.put("duration", duration);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("modified_drag", body);
        return actionOutcome(String.join("+", keys) + "+drag (" + fromX + "," + fromY + ")→(" + toX + "," + toY + ")", r, pre, true);
    }

    private Outcome mousePath(JsonNode args) {
        List<List<Integer>> points = parsePointList(args, "points");
        if (points.size() < 2) throw new IllegalArgumentException("mouse_path requires points[] of [x,y] (min 2)");
        double duration = args.path("duration").asDouble(0.6);
        String button = text(args, "button", "left");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", points); body.put("duration", duration); body.put("button", button);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("mouse_path", body);
        return actionOutcome("mouse_path " + points.size() + " pts", r, pre, true);
    }

    private Outcome scrubSlider(JsonNode args) {
        List<List<Integer>> points = parsePointList(args, "points");
        if (points.size() < 2) throw new IllegalArgumentException("scrub_slider requires points[] (min 2)");
        int settleMs = Math.max(0, args.path("settle_ms").asInt(80));
        String button = text(args, "button", "left");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", points); body.put("settle_ms", settleMs); body.put("button", button);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("scrub_slider", body);
        return actionOutcome("scrub_slider " + points.size() + " pts", r, pre, true);
    }

    private Outcome dragHoldObserveRelease(JsonNode args) {
        Integer fromX = optInt(args, "from_x");
        Integer fromY = optInt(args, "from_y");
        Integer toX = optInt(args, "to_x");
        Integer toY = optInt(args, "to_y");
        if (fromX == null || fromY == null || toX == null || toY == null) {
            throw new IllegalArgumentException("drag_hold_observe_release requires from_x,y to_x,y");
        }
        int holdMs = Math.max(100, args.path("hold_ms").asInt(800));
        String button = text(args, "button", "left");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_x", fromX); body.put("from_y", fromY);
        body.put("to_x", toX); body.put("to_y", toY);
        body.put("hold_ms", holdMs); body.put("button", button);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("drag_hold_observe_release", body);
        return actionOutcome("drag-hold-observe-release (" + fromX + "," + fromY + ")→(" + toX + "," + toY + ") hold " + holdMs + "ms", r, pre, true);
    }

    private Outcome nudge(JsonNode args) {
        String key = text(args, "key", "");
        if (key.isBlank()) throw new IllegalArgumentException("nudge requires key");
        int count = Math.max(1, args.path("count").asInt(1));
        int intervalMs = Math.max(0, args.path("interval_ms").asInt(30));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key); body.put("count", count); body.put("interval_ms", intervalMs);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("nudge", body);
        return actionOutcome("nudge " + key + " ×" + count, r, pre, true);
    }

    private List<String> parseKeyList(JsonNode args, String... names) {
        for (String n : names) {
            JsonNode node = args.path(n);
            if (node.isArray()) {
                List<String> out = new ArrayList<>();
                for (JsonNode k : node) {
                    String s = k.asText("").trim();
                    if (!s.isBlank()) out.add(s);
                }
                if (!out.isEmpty()) return out;
            } else if (node.isTextual() && !node.asText().isBlank()) {
                return List.of(node.asText());
            }
        }
        return List.of();
    }

    private List<List<Integer>> parsePointList(JsonNode args, String name) {
        JsonNode pts = args.path(name);
        List<List<Integer>> out = new ArrayList<>();
        if (!pts.isArray()) return out;
        for (JsonNode p : pts) {
            if (p.isArray() && p.size() >= 2) {
                out.add(List.of(p.get(0).asInt(), p.get(1).asInt()));
            } else if (p.has("x") && p.has("y")) {
                out.add(List.of(p.get("x").asInt(), p.get("y").asInt()));
            }
        }
        return out;
    }

    /** Raw pixel-based drag from (from_x,from_y) to (to_x,to_y). The Python
     *  guest controller exposes drag_between under /hands/action — we forward
     *  with optional duration_seconds (default 0.3 s). Use for: moving a
     *  textbox, drawing a selection rectangle around objects, dragging a slide
     *  in the slide-panel, resizing via a corner handle. */
    private Outcome drag(JsonNode args) {
        Integer fromX = optInt(args, "from_x");
        Integer fromY = optInt(args, "from_y");
        if (fromX == null) fromX = optInt(args, "x1");
        if (fromY == null) fromY = optInt(args, "y1");
        Integer toX = optInt(args, "to_x");
        Integer toY = optInt(args, "to_y");
        if (toX == null) toX = optInt(args, "x2");
        if (toY == null) toY = optInt(args, "y2");
        if (fromX == null || fromY == null || toX == null || toY == null) {
            throw new IllegalArgumentException("drag requires from_x, from_y, to_x, to_y");
        }
        double duration = args.path("duration_seconds").asDouble(
                args.path("duration").asDouble(0.3));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_x", fromX); body.put("from_y", fromY);
        body.put("to_x", toX);     body.put("to_y", toY);
        body.put("duration", duration);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("drag_between", body);
        return actionOutcome("dragged (" + fromX + "," + fromY + ")→(" + toX + "," + toY + ")",
                r, pre, true);
    }

    /** OmniParser box-id drag: drag the box labelled `box` to the box labelled
     *  `to_box`. Java forwards as left_click_drag — the Python controller
     *  resolves both box ids to pixel centres then performs the drag. */
    private Outcome dragBox(JsonNode args) {
        Integer fromBox = optInt(args, "box");
        if (fromBox == null) fromBox = optInt(args, "box_id");
        if (fromBox == null) fromBox = optInt(args, "from_box");
        Integer toBox = optInt(args, "to_box");
        if (toBox == null) toBox = optInt(args, "to_box_id");
        if (fromBox == null || toBox == null) {
            throw new IllegalArgumentException("drag_box requires box (or box_id) and to_box");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("box_id", fromBox);
        body.put("to_box_id", toBox);
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("left_click_drag", body);
        return actionOutcome("dragged box " + fromBox + " → " + toBox, r, pre, true);
    }

    private Outcome waitTool(JsonNode args) {
        double seconds = Math.max(0.1, args.path("seconds").asDouble(args.path("duration").asDouble(1.0)));
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.hands("wait", Map.of("seconds", seconds));
        return actionOutcome("waited " + seconds + "s", r, pre, false);
    }

    private Outcome launchApp(JsonNode args) {
        String name = firstText(args, "name", "target");
        String desktopFile = text(args, "desktop_file", "");
        String exec = text(args, "exec", "");
        if ((name == null || name.isBlank()) && desktopFile.isBlank() && exec.isBlank()) {
            throw new IllegalArgumentException("launch_app requires name, target, desktop_file, or exec");
        }
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = desktopFile.isBlank() && exec.isBlank()
                ? sandbox.launchApp(name)
                : sandbox.launchDesktopApp(name, desktopFile, exec);
        if (!r.ok() && r.status() == 404) {
            Optional<Sandbox.DesktopApp> resolved = resolveLaunchAppFromCatalog(name, desktopFile, exec);
            if (resolved.isPresent()) {
                Sandbox.DesktopApp app = resolved.get();
                log.info("[launch_app] retrying via catalog name={} desktop_file={}",
                        app.name(), app.desktopFile());
                r = sandbox.launchDesktopApp(app.name(), app.desktopFile(), app.exec());
            }
        }
        String label = !desktopFile.isBlank() ? desktopFile : (!exec.isBlank() ? exec : name);
        return actionOutcome("launched " + label, r, pre, false);
    }

    private Optional<Sandbox.DesktopApp> resolveLaunchAppFromCatalog(String name, String desktopFile, String exec) {
        String query = firstNonBlank(name, desktopFile, exec).trim();
        if (query.isBlank()) return Optional.empty();
        String q = query.toLowerCase(Locale.ROOT);
        String base = Path.of(query).getFileName() == null ? q
                : Path.of(query).getFileName().toString().toLowerCase(Locale.ROOT);
        List<Sandbox.DesktopApp> apps = sandbox.appsCatalog(500);
        Sandbox.DesktopApp best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Sandbox.DesktopApp app : apps) {
            String appName = nullToBlank(app.name()).toLowerCase(Locale.ROOT);
            String appDesktop = nullToBlank(app.desktopFile()).toLowerCase(Locale.ROOT);
            String appExec = nullToBlank(app.exec()).toLowerCase(Locale.ROOT);
            String appBase = Path.of(appDesktop).getFileName() == null ? appDesktop
                    : Path.of(appDesktop).getFileName().toString().toLowerCase(Locale.ROOT);
            int score = Integer.MAX_VALUE;
            if (!q.isBlank() && (q.equals(appName) || q.equals(appDesktop) || q.equals(appExec))) score = 0;
            else if (!base.isBlank() && base.equals(appBase)) score = 1;
            else if (!q.isBlank() && (appName.contains(q) || appDesktop.contains(q) || appExec.contains(q))) score = 2;
            else if (!q.isBlank() && q.equals("gimp")
                    && (appName.contains("image manipulation") || appExec.contains("gimp"))) score = 3;
            if (score < bestScore) {
                best = app;
                bestScore = score;
            }
        }
        return best == null ? Optional.empty() : Optional.of(best);
    }

    private Outcome activateWindow(JsonNode args) {
        String name = firstText(args, "name", "target", "app", "window", "process");
        String title = firstText(args, "title", "window_title");
        String xid = firstText(args, "xid", "window_id", "id");
        if (name.isBlank() && title.isBlank() && xid.isBlank()) {
            throw new IllegalArgumentException("activate_window requires name, title, target, app, or xid");
        }
        byte[] pre = captureActionBaseline();
        Sandbox.Response r = sandbox.activateWindow(name, title, xid);
        String label = !title.isBlank() ? title : (!name.isBlank() ? name : xid);
        return actionOutcome("activated window " + label, r, pre, false);
    }

    private Outcome closeWindow(JsonNode args) {
        String name = firstText(args, "name", "target", "app", "window", "title");
        String title = firstText(args, "title", "window_title");
        String xid = firstText(args, "xid", "window_id", "id");
        if (name.isBlank() && title.isBlank() && xid.isBlank()) {
            throw new IllegalArgumentException("close_window requires name, title, target, or xid");
        }
        byte[] pre = captureActionBaseline();
        Map<String, Object> body = new LinkedHashMap<>();
        if (!name.isBlank()) body.put("name", name);
        if (!title.isBlank()) body.put("title", title);
        if (!xid.isBlank()) body.put("xid", xid);
        Sandbox.Response r = sandbox.hands("close_window", body);
        String label = !title.isBlank() ? title : (!name.isBlank() ? name : xid);
        return actionOutcome("closed window " + label, r, pre, false);
    }

    private Outcome listApps(JsonNode args) {
        String query = firstText(args, "query", "name", "target", "app").trim();
        int limit = Math.max(1, args == null ? 40 : args.path("limit").asInt(query.isBlank() ? 40 : 20));
        List<Sandbox.DesktopApp> apps = sandbox.appsCatalog(query.isBlank() ? limit : 0);
        if (apps.isEmpty()) {
            return Outcome.fail("list_apps: no apps returned from sandbox catalog");
        }
        if (!query.isBlank()) {
            List<Sandbox.DesktopApp> filtered = new ArrayList<>();
            for (Sandbox.DesktopApp app : apps) {
                if (matchesAppQuery(app, query)) filtered.add(app);
                if (filtered.size() >= limit) break;
            }
            apps = filtered;
            if (apps.isEmpty()) {
                return Outcome.fail("list_apps: no apps matched query '" + query + "'");
            }
        } else if (apps.size() > limit) {
            apps = apps.subList(0, limit);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("installed apps (launch_app must use exact name or desktop_file):");
        if (!query.isBlank()) sb.append(" query=").append(query);
        for (Sandbox.DesktopApp app : apps) {
            if (app == null) continue;
            sb.append("\n- ").append(app.name());
            if (app.desktopFile() != null && !app.desktopFile().isBlank()) {
                sb.append(" | desktop_file=").append(app.desktopFile());
            } else if (app.exec() != null && !app.exec().isBlank()) {
                sb.append(" | exec=").append(app.exec());
            }
        }
        return Outcome.ok(sb.toString(), null);
    }

    private static boolean matchesAppQuery(Sandbox.DesktopApp app, String query) {
        if (app == null || query == null || query.isBlank()) return true;
        String haystack = (app.name() + " " + app.desktopFile() + " " + app.exec()
                + " " + app.description()).toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (haystack.contains(q)) return true;
        for (String term : splitOnChar(q, ' ')) {
            String t = term.trim();
            if (t.length() < 2) continue;
            if (haystack.contains(t)) return true;
        }
        return false;
    }

    private Outcome writeFile(JsonNode args) {
        String path = requiredText(args, "path");
        String content = requiredText(args, "content");
        Sandbox.Response r = sandbox.writeFile(path, content);
        if (!r.ok()) return Outcome.fail("write_file failed HTTP " + r.status() + ": " + truncate(r.body(), 400));
        byte[] post = screenshotLabeled();
        traceSave("write_file", post, null, null);
        return Outcome.ok("wrote " + content.length() + " chars to " + path, post);
    }

    private Outcome readFile(JsonNode args) {
        String path = requiredText(args, "path");
        Sandbox.Response r = sandbox.readFile(path);
        if (!r.ok()) return Outcome.fail("read_file failed HTTP " + r.status() + ": " + truncate(r.body(), 400));
        byte[] post = screenshotLabeled();
        traceSave("read_file", post, null, null);
        return Outcome.ok("read_file " + path + ":\n" + truncate(r.body(), 4000), post);
    }

    private Outcome done(JsonNode args) {
        return Outcome.done(requiredText(args, "summary"));
    }

    private Outcome actionOutcome(String summary, Sandbox.Response r) {
        return actionOutcome(summary, r, null, false);
    }

    private Outcome actionOutcome(String summary, Sandbox.Response r, byte[] pre, boolean failOnNoVisibleChange) {
        if (!r.ok()) {
            return Outcome.fail(summary + " failed HTTP " + r.status() + ": " + truncate(r.body(), 400));
        }
        byte[] post = screenshotLabeled();
        String tag = summary == null || summary.isBlank() ? "action" : firstToken(summary);
        tracePair(tag, pre, post, null, null);
        return visibleActionOutcome(summary, post, screenDelta(pre, post), failOnNoVisibleChange);
    }

    private Sandbox.Response humanizedClick(Map<String, Object> clickBody, int x, int y) {
        Sandbox.Response move = sandbox.hands("move", Map.of("x", x, "y", y));
        if (!move.ok()) {
            log.warn("[click.humanize] move before click failed HTTP {}: {}",
                    move.status(), truncate(move.body(), 200));
        } else {
            sleepForClick(clickHoverDelayMs);
        }
        String type = useLongClick(clickBody) ? "long_click" : "click";
        Map<String, Object> body = clickBody;
        if ("long_click".equals(type)) {
            body = new LinkedHashMap<>(clickBody);
            body.remove("clicks");
            body.put("duration_ms", Math.max(1, clickHoldMs));
        }
        Sandbox.Response click = sandbox.hands(type, body);
        sleepForClick(clickPostDelayMs);
        return click;
    }

    private boolean useLongClick(Map<String, Object> clickBody) {
        if (!clickLongPressEnabled) return false;
        if (clickBody == null) return true;
        Object button = clickBody == null ? null : clickBody.get("button");
        String buttonText = button == null ? "left" : button.toString().trim().toLowerCase(Locale.ROOT);
        if (!"left".equals(buttonText)) return false;
        Object clicks = clickBody.get("clicks");
        if (clicks == null) return true;
        if (clicks instanceof Number n) return n.intValue() <= 1;
        try {
            return Integer.parseInt(clicks.toString().trim()) <= 1;
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private Outcome visibleActionOutcome(String summary, byte[] post,
            Map<String, String> meta, boolean failOnNoVisibleChange) {
        // visible_change/screen_delta_pct is advisory telemetry only and is unreliable
        // on this sandbox. Do NOT inject "no visible change" into the LLM-facing
        // summary — that text used to mislead the planner into invalidating routes
        // for clicks that actually landed. The metadata fields remain in `meta`
        // for telemetry and UI display, but the summary stays clean.
        return Outcome.ok(summary, post, meta);
    }

    private static void sleepForClick(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ix) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ix);
        }
    }

    private Outcome screenshot() {
        byte[] png = screenshotLabeled();
        traceSave("screenshot", png, null, null);
        return Outcome.ok("screenshot " + png.length + " bytes", png);
    }

    private static List<String> keyList(JsonNode args) {
        List<String> out = new ArrayList<>();
        JsonNode keys = args.path("keys");
        if (keys.isArray()) {
            for (JsonNode k : keys) {
                String v = normalizeKey(k.asText(""));
                if (!v.isBlank()) out.add(v);
            }
            return out;
        }
        String combo = text(args, "combo", text(args, "key", ""));
        for (String part : splitOnChar(combo, '+')) {
            String v = normalizeKey(part);
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    private byte[] captureActionBaseline() {
        if (!Boolean.TRUE.equals(RAW_SCREENSHOTS.get())) return null;
        try {
            return sandbox.screenshot();
        } catch (RuntimeException ex) {
            log.debug("[screen_delta] baseline capture failed: {}", ex.toString());
            return null;
        }
    }

    static Map<String, String> screenDelta(byte[] before, byte[] after) {
        if (before == null || before.length == 0 || after == null || after.length == 0) {
            return Map.of();
        }
        try {
            BufferedImage a = ImageIO.read(new ByteArrayInputStream(before));
            BufferedImage b = ImageIO.read(new ByteArrayInputStream(after));
            if (a == null || b == null) return Map.of();
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                return Map.of("screen_delta", "unknown_dimensions_changed");
            }
            int width = a.getWidth();
            int height = a.getHeight();
            int sxCount = Math.max(1, Math.min(SCREEN_DELTA_GRID, width));
            int syCount = Math.max(1, Math.min(SCREEN_DELTA_GRID, height));
            int changed = 0;
            int total = sxCount * syCount;
            for (int sy = 0; sy < syCount; sy++) {
                int y = Math.min(height - 1, (sy * height) / syCount);
                for (int sx = 0; sx < sxCount; sx++) {
                    int x = Math.min(width - 1, (sx * width) / sxCount);
                    if (rgbDistance(a.getRGB(x, y), b.getRGB(x, y)) >= SCREEN_DELTA_RGB_THRESHOLD) {
                        changed++;
                    }
                }
            }
            double ratio = total == 0 ? 0.0 : changed / (double) total;
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("screen_delta_pct", String.format(Locale.ROOT, "%.2f", ratio * 100.0));
            meta.put("visible_change", Boolean.toString(ratio >= VISIBLE_CHANGE_THRESHOLD));
            meta.put("changed_samples", changed + "/" + total);
            return meta;
        } catch (Exception ex) {
            log.debug("[screen_delta] diff failed: {}", ex.toString());
            return Map.of("screen_delta", "unknown_error");
        }
    }

    private static int rgbDistance(int a, int b) {
        int ar = (a >> 16) & 0xff;
        int ag = (a >> 8) & 0xff;
        int ab = a & 0xff;
        int br = (b >> 16) & 0xff;
        int bg = (b >> 8) & 0xff;
        int bb = b & 0xff;
        return Math.abs(ar - br) + Math.abs(ag - bg) + Math.abs(ab - bb);
    }

    private static String firstToken(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return "action";
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) return t.substring(0, i);
        }
        return t;
    }

    private static List<String> splitOnChar(String text, char delimiter) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) return parts;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == delimiter) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private static String normalizeKey(String raw) {
        String k = raw == null ? "" : raw.trim().toLowerCase();
        return switch (k) {
            case "return" -> "enter";
            case "esc" -> "escape";
            case "control" -> "ctrl";
            case "command", "cmd" -> "super";
            default -> k;
        };
    }

    private static int requiredInt(JsonNode args, String name) {
        if (args == null || !args.has(name)) throw new IllegalArgumentException(name + " is required");
        return args.path(name).asInt();
    }

    private static String requiredText(JsonNode args, String name) {
        String v = text(args, name, "");
        if (v.isBlank()) throw new IllegalArgumentException(name + " is required");
        return v;
    }

    private static String firstText(JsonNode args, String... names) {
        if (names == null) return "";
        for (String name : names) {
            String v = text(args, name, "");
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String v = nullToBlank(value);
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode args, String name, String fallback) {
        if (args == null || !args.has(name) || args.path(name).isNull()) return fallback;
        return args.path(name).asText(fallback);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "\n…[truncated]";
    }
}
