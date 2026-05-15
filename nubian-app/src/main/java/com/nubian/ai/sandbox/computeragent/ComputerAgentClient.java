package com.nubian.ai.sandbox.computeragent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.DesktopApp;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.ExecResult;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.FileBlob;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.FileEntry;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.HealthResponse;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.LaunchResult;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.ObserveResult;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.SkillEntry;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.SkillRunResult;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.SqlResult;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.WindowInfo;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.WriteResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for any Ubuntu-desktop guest agent running on port 6090.
 * Uses {@link java.net.http.HttpClient} — no Spring Web dependency.
 *
 * <p>Non-2xx responses throw {@link ComputerAgentException} (RuntimeException).
 * IOExceptions are wrapped; InterruptedException re-sets the interrupt flag before wrapping.
 */
public class ComputerAgentClient {

    private final ComputerAgentEndpoints endpoints;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public ComputerAgentClient(ComputerAgentEndpoints endpoints, ObjectMapper mapper, Duration requestTimeout) {
        this(endpoints, mapper, requestTimeout,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    /** Package-private constructor for tests that supply a custom HttpClient. */
    ComputerAgentClient(ComputerAgentEndpoints endpoints, ObjectMapper mapper, Duration requestTimeout,
                        HttpClient httpClient) {
        this.endpoints = endpoints;
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
        this.httpClient = httpClient;
    }

    /** Returns the endpoint coordinates this client is configured to talk to. */
    public ComputerAgentEndpoints endpoints() { return endpoints; }

    // -------------------------------------------------------------------------
    // Health (not in the adapter interface — typed convenience)
    // -------------------------------------------------------------------------

    public HealthResponse health() {
        String body = sendString(get("/health"), "/health");
        return parse(body, HealthResponse.class, "/health");
    }

    // -------------------------------------------------------------------------
    // Eyes — adapter interface methods
    // -------------------------------------------------------------------------


    public byte[] screenshot() {
        return sendBytes(builder("/eyes/screenshot").GET().build(), "/eyes/screenshot");
    }

    /**
     * {@code GET /agent/codeact/screenshot} — observation half of the CodeAct
     * contract. Returns a PNG of the live desktop at native resolution. Use
     * this BEFORE asking the model for its next Python snippet so the
     * coordinate space the model sees matches what {@link #codeactPython}
     * will execute against.
     */
    public byte[] codeactScreenshot() {
        return sendBytes(builder("/agent/codeact/screenshot").GET().build(), "/agent/codeact/screenshot");
    }

    /**
     * {@code POST /agent/codeact/python} — action half of the CodeAct
     * contract. Runs the supplied Python source in a long-lived runtime that
     * has pyautogui, requests, websocket, cdp_base/cdp_url()/cdp_get()/cdp_post(),
     * plus os/time/pathlib/subprocess/json/math/sys/shutil pre-loaded. Returns
     * the JSON envelope the guest produced (stdout, stderr, traceback, return
     * value).
     */
    public JsonNode codeactPython(String code, int timeoutMs) {
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.put("code", code == null ? "" : code);
        if (timeoutMs > 0) body.put("timeout_ms", timeoutMs);
        HttpRequest req = post("/agent/codeact/python", toJson(body, "/agent/codeact/python"));
        String response = sendString(req, "/agent/codeact/python");
        return parseTree(response, "/agent/codeact/python");
    }


    public JsonNode accessibility() {
        String body = sendString(get("/eyes/accessibility"), "/eyes/accessibility");
        return parseTree(body, "/eyes/accessibility");
    }

    /**
     * {@code GET /eyes/observe} — single-shot "state of the world" snapshot:
     * focused window, all running windows with bbox, recent files, installed
     * apps, AT-SPI tree, last action result. Limits are advisory; the guest
     * caps each list to its own internal maximum.
     */
    public ObserveResult observe(int eventLimit, int appsLimit, int filesLimit) {
        StringBuilder qs = new StringBuilder("/eyes/observe");
        boolean first = true;
        if (eventLimit > 0) { qs.append('?').append("event_limit=").append(eventLimit); first = false; }
        if (appsLimit > 0) { qs.append(first ? '?' : '&').append("apps_limit=").append(appsLimit); first = false; }
        if (filesLimit > 0) { qs.append(first ? '?' : '&').append("files_limit=").append(filesLimit); }
        String ep = qs.toString();
        String body = sendString(get(ep), ep);
        return parse(body, ObserveResult.class, ep);
    }

    /** {@code GET /eyes/observe} with guest defaults. */
    public ObserveResult observe() {
        return observe(0, 0, 0);
    }

    /**
     * {@code POST /eyes/sql} — read-only DuckDB query against
     * {@code /logs/events.duckdb}. Server-side validates the query is
     * read-only; non-SELECT statements return {@code ok=false} with an error.
     */
    public SqlResult sqlQuery(String sql, int limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sql", sql);
        if (limit > 0) payload.put("limit", limit);
        // The DuckDB file is shared with the live indexer — sporadic
        // "Could not set lock on file" errors are expected. Retry with
        // short backoff before surfacing the failure.
        ComputerAgentException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                HttpRequest req = post("/eyes/sql", toJson(payload, "/eyes/sql"));
                String body = sendString(req, "/eyes/sql");
                return parse(body, SqlResult.class, "/eyes/sql");
            } catch (ComputerAgentException ex) {
                if (!isDuckDbLockError(ex)) throw ex;
                last = ex;
            }
        }
        throw last;
    }

    /**
     * Convenience for the "what changed?" pattern: returns the maximum
     * {@code seq} currently in the event store. Pair with {@link #sqlQuery}
     * after each action to grep rows {@code WHERE seq > :start_seq}.
     */
    public long maxEventSeq() {
        SqlResult result = sqlQuery("SELECT coalesce(max(seq), 0) AS max_seq FROM events", 1);
        if (result == null || result.rows() == null || result.rows().isEmpty()) return 0L;
        return result.rows().get(0).get(0).asLong(0L);
    }

    /**
     * {@code GET /eyes/events/around?seq=...&before=...&after=...} — fetch
     * a window of raw event rows around a {@code seq} of interest. Use this
     * to drill into a dialog/focus event after the grouped summary points
     * the agent at the right moment in time.
     */
    public JsonNode eventsAround(long seq, int before, int after) {
        StringBuilder ep = new StringBuilder("/eyes/events/around?seq=").append(seq);
        if (before > 0) ep.append("&before=").append(before);
        if (after > 0) ep.append("&after=").append(after);
        String body = sendString(get(ep.toString()), ep.toString());
        return parseTree(body, ep.toString());
    }

    private static boolean isDuckDbLockError(ComputerAgentException ex) {
        String msg = ex.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("could not set lock") || lower.contains("database is locked");
    }

    // -------------------------------------------------------------------------
    // Apps — installed-app discovery and launching (no Super-key guessing)
    // -------------------------------------------------------------------------

    /** {@code GET /apps?limit=N} — list .desktop apps the guest discovered. */
    public List<DesktopApp> listApps(int limit) {
        String ep = "/apps" + (limit > 0 ? "?limit=" + limit : "");
        String body = sendString(get(ep), ep);
        return parseAppList(body, ep);
    }

    /**
     * {@code GET /apps/catalog?limit=N} — clean catalog of installed apps with
     * exact official names. The launcher only accepts these names (or
     * {@code desktop_file} paths). No fuzzy shortcut matching.
     */
    public List<DesktopApp> getAppsCatalog(int limit) {
        String ep = "/apps/catalog" + (limit > 0 ? "?limit=" + limit : "");
        String body = sendString(get(ep), ep);
        return parseAppList(body, ep);
    }

    /** {@code GET /apps/search?q=...} — fuzzy-match installed apps. */
    public List<DesktopApp> searchApps(String query, int limit) {
        String encoded = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        String ep = "/apps/search?q=" + encoded + (limit > 0 ? "&limit=" + limit : "");
        String body = sendString(get(ep), ep);
        return parseAppList(body, ep);
    }

    /**
     * {@code POST /apps/launch} — launch by exact official {@code name} from
     * {@link #getAppsCatalog(int)} (or a {@code .desktop} file path). The
     * launcher no longer fuzzy-matches; callers must resolve a real catalog
     * name first.
     */
    public LaunchResult launchApp(String name, String execOverride) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) {
            if (name.endsWith(".desktop")) payload.put("desktop_file", name);
            else payload.put("name", name);
        }
        if (execOverride != null && !execOverride.isBlank()) payload.put("exec", execOverride);
        payload.put("fullscreen", false);
        payload.put("maximize", false);
        payload.put("force_maximize", false);
        HttpRequest req = post("/apps/launch", toJson(payload, "/apps/launch"));
        String body = sendString(req, "/apps/launch");
        return parse(body, LaunchResult.class, "/apps/launch");
    }

    // -------------------------------------------------------------------------
    // Windows — focus a running X11 window without GUI guessing
    // -------------------------------------------------------------------------

    /**
     * {@code POST /windows/activate} — focus a window by xid, wm_class, or
     * title substring. The guest picks the best match via wmctrl.
     */
    public LaunchResult activateWindow(String xid, String app, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (xid != null && !xid.isBlank()) payload.put("xid", xid);
        if (app != null && !app.isBlank()) payload.put("app", app);
        if (title != null && !title.isBlank()) payload.put("title", title);
        payload.put("fullscreen", false);
        payload.put("maximize", false);
        payload.put("force_maximize", false);
        HttpRequest req = post("/windows/activate", toJson(payload, "/windows/activate"));
        String body = sendString(req, "/windows/activate");
        return parse(body, LaunchResult.class, "/windows/activate");
    }

    /** Convenience: list running windows by reading the {@link ObserveResult}. */
    public List<WindowInfo> listWindows() {
        ObserveResult result = observe(0, 0, 0);
        List<WindowInfo> windows = result == null ? null : result.runningWindows();
        return windows == null ? List.of() : windows;
    }

    // -------------------------------------------------------------------------
    // Skills — pre-canned, file-shaped shortcuts that bypass the GUI entirely
    // -------------------------------------------------------------------------

    /** {@code GET /skills/search?q=...} — fuzzy-match available skills. */
    public List<SkillEntry> searchSkills(String query, int limit) {
        String encoded = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        String ep = "/skills/search?q=" + encoded + (limit > 0 ? "&limit=" + limit : "");
        String body = sendString(get(ep), ep);
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode array = root.isArray() ? root : root.path("skills");
            return mapper.convertValue(array, new TypeReference<List<SkillEntry>>() {});
        } catch (Exception ex) {
            throw new ComputerAgentException(200, ep,
                    "Failed to parse skill list: " + ex.getMessage(), ex);
        }
    }

    /** {@code POST /skills/run} — invoke a skill by id with parameters. */
    public SkillRunResult runSkill(String skillId, JsonNode parameters) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", skillId);
        if (parameters != null && !parameters.isMissingNode() && !parameters.isNull()) {
            payload.put("parameters", parameters);
        }
        HttpRequest req = post("/skills/run", toJson(payload, "/skills/run"));
        String body = sendString(req, "/skills/run");
        return parse(body, SkillRunResult.class, "/skills/run");
    }

    private List<DesktopApp> parseAppList(String body, String ep) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode array = root.isArray() ? root : root.path("apps");
            if (array == null || array.isMissingNode() || array.isNull() || !array.isArray()) {
                return List.of();
            }
            List<DesktopApp> parsed = mapper.convertValue(array, new TypeReference<List<DesktopApp>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (Exception ex) {
            throw new ComputerAgentException(200, ep,
                    "Failed to parse app list: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Hands — adapter interface methods
    // -------------------------------------------------------------------------

    /**
     * Dispatches a pyautogui action. The body JSON is forwarded as-is to the guest agent.
     * Returns the raw JSON response node.
     */

    public JsonNode pyautogui(JsonNode body) {
        HttpRequest req = post("/hands/pyautogui", toJson(body, "/hands/pyautogui"));
        String response = sendString(req, "/hands/pyautogui");
        return parseTree(response, "/hands/pyautogui");
    }

    /**
     * Runs an arbitrary Python script inside the guest. The body must contain
     * a {@code "code"} string with bare Python source (no markdown fences) and
     * an optional {@code "timeout_ms"}. Returns the guest's raw JSON which
     * typically carries {@code stdout}, {@code stderr}, {@code returncode},
     * {@code blocked_pyautogui}, and {@code timeout_ms}.
     */
    public JsonNode python(String code, int timeoutMs) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("code", code == null ? "" : code);
        if (timeoutMs > 0) payload.put("timeout_ms", timeoutMs);
        HttpRequest req = post("/hands/python", toJson(payload, "/hands/python"));
        String response = sendString(req, "/hands/python");
        return parseTree(response, "/hands/python");
    }

    /**
     * Dispatches a structured hand action ({@code {"type":"click","x":..,"y":..}},
     * {@code {"type":"type","text":".."}}, {@code {"type":"hotkey","keys":[..]}},
     * {@code {"type":"scroll","amount":±N}}, {@code {"type":"wait","seconds":N}}).
     *
     * <p>Posts to {@code /hands/action} which the guest agent splits from
     * {@link #pyautogui(JsonNode)} (raw pyautogui code). Use this for the
     * common click/type/key/scroll/wait verbs.
     */
    public JsonNode handsAction(JsonNode body) {
        HttpRequest req = post("/hands/action", toJson(body, "/hands/action"));
        String response = sendString(req, "/hands/action");
        return parseTree(response, "/hands/action");
    }

    /**
     * Dispatches an xdotool action. The body JSON is forwarded as-is to the guest agent.
     * Returns the raw JSON response node.
     */

    public JsonNode xdotool(JsonNode body) {
        HttpRequest req = post("/hands/xdotool", toJson(body, "/hands/xdotool"));
        String response = sendString(req, "/hands/xdotool");
        return parseTree(response, "/hands/xdotool");
    }

    /**
     * Convenience overload for xdotool that takes a plain command string.
     * Wraps the command as {@code {"cmd": "..."}} and returns a typed result.
     */
    public com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.ActionResult xdotool(String cmd) {
        Map<String, String> payload = Map.of("cmd", cmd);
        HttpRequest req = post("/hands/xdotool", toJson(payload, "/hands/xdotool"));
        String response = sendString(req, "/hands/xdotool");
        return parse(response, com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.ActionResult.class, "/hands/xdotool");
    }

    // -------------------------------------------------------------------------
    // Shell — adapter interface method
    // -------------------------------------------------------------------------

    /**
     * Executes a shell command. The body JSON is forwarded as-is to the guest agent.
     * Returns the raw JSON response node.
     */

    public JsonNode exec(JsonNode body) {
        HttpRequest req = post("/shell/exec", toJson(body, "/shell/exec"));
        String response = sendString(req, "/shell/exec");
        return parseTree(response, "/shell/exec");
    }

    /**
     * Convenience overload for exec that takes typed parameters and returns a typed result.
     */
    public ExecResult exec(String cmd, String cwd, long timeoutMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmd", cmd);
        payload.put("cwd", cwd != null ? cwd : "/workspace");
        payload.put("timeout_ms", timeoutMs);
        HttpRequest req = post("/shell/exec", toJson(payload, "/shell/exec"));
        String response = sendString(req, "/shell/exec");
        return parse(response, ExecResult.class, "/shell/exec");
    }

    // -------------------------------------------------------------------------
    // Memory / Files (not in adapter interface — typed convenience)
    // -------------------------------------------------------------------------

    public List<FileEntry> listFiles(String path) {
        String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
        String ep = "/memory/files/list?path=" + encoded;
        String body = sendString(get(ep), ep);
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode entries = root.has("entries") ? root.get("entries") : root;
            return mapper.convertValue(entries, new TypeReference<List<FileEntry>>() {});
        } catch (Exception ex) {
            throw new ComputerAgentException(200, ep, "Failed to parse file list: " + ex.getMessage(), ex);
        }
    }

    public FileBlob readFile(String path) {
        String encoded = URLEncoder.encode(path, StandardCharsets.UTF_8);
        String ep = "/memory/files?path=" + encoded;
        HttpRequest req = builder(ep).header("Accept", "*/*").GET().build();
        try {
            HttpResponse<byte[]> response = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(response.statusCode(), ep, new String(response.body(), StandardCharsets.UTF_8));
            String ct = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            return new FileBlob(response.body(), ct);
        } catch (ComputerAgentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ComputerAgentException(-1, ep, "IO error: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ComputerAgentException(-1, ep, "Interrupted: " + ex.getMessage(), ex);
        }
    }

    public WriteResult writeFile(String path, byte[] content) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("path", path);
        payload.put("content", new String(content, StandardCharsets.UTF_8));
        HttpRequest req = post("/memory/files", toJson(payload, "/memory/files"));
        String body = sendString(req, "/memory/files");
        return parse(body, WriteResult.class, "/memory/files");
    }

    // -------------------------------------------------------------------------
    // Viewer / VNC (not in adapter interface — typed convenience)
    // -------------------------------------------------------------------------

    /** Issues GET /viewer/vnc and returns the Location redirect target. */
    public String vncRedirectUrl() {
        String ep = "/viewer/vnc";
        HttpRequest req = builder(ep).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                return response.headers().firstValue("Location").orElse("");
            }
            checkStatus(response.statusCode(), ep, response.body());
            return response.body().trim();
        } catch (ComputerAgentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ComputerAgentException(-1, ep, "IO error: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ComputerAgentException(-1, ep, "Interrupted: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Browser / CDP — adapter interface methods
    // -------------------------------------------------------------------------


    public JsonNode cdpVersion() {
        String ep = "/browser/cdp/json/version";
        String body = sendString(get(ep), ep);
        return parseTree(body, ep);
    }


    public JsonNode cdpCommand(String method, JsonNode params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", method);
        payload.put("params", params != null ? params : mapper.createObjectNode());
        String ep = "/browser/cdp/command";
        HttpRequest req = post(ep, toJson(payload, ep));
        String body = sendString(req, ep);
        return parseTree(body, ep);
    }

    // -------------------------------------------------------------------------
    // Logs (not in adapter interface — typed convenience)
    // -------------------------------------------------------------------------

    public List<JsonNode> actionLog(int limit) {
        String ep = "/logs/actions?limit=" + limit;
        String body = sendString(get(ep), ep);
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode array = root.isArray() ? root : root.path("actions");
            return mapper.convertValue(array, new TypeReference<List<JsonNode>>() {});
        } catch (Exception ex) {
            throw new ComputerAgentException(200, ep, "Failed to parse action log: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Request builders
    // -------------------------------------------------------------------------

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create(endpoints.agentBaseUrl() + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
    }

    private HttpRequest get(String path) {
        return builder(path).GET().build();
    }

    private HttpRequest post(String path, String jsonBody) {
        return builder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
    }

    // -------------------------------------------------------------------------
    // Send helpers
    // -------------------------------------------------------------------------

    private String sendString(HttpRequest req, String endpoint) {
        try {
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            checkStatus(response.statusCode(), endpoint, response.body());
            return response.body();
        } catch (ComputerAgentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ComputerAgentException(-1, endpoint, "IO error: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ComputerAgentException(-1, endpoint, "Interrupted: " + ex.getMessage(), ex);
        }
    }

    private byte[] sendBytes(HttpRequest req, String endpoint) {
        try {
            HttpResponse<byte[]> response = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(response.statusCode(), endpoint, new String(response.body(), StandardCharsets.UTF_8));
            return response.body();
        } catch (ComputerAgentException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ComputerAgentException(-1, endpoint, "IO error: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ComputerAgentException(-1, endpoint, "Interrupted: " + ex.getMessage(), ex);
        }
    }

    private static void checkStatus(int status, String endpoint, String body) {
        if (status < 200 || status >= 300) {
            String preview = body != null && body.length() > 1000 ? body.substring(0, 1000) : body;
            throw new ComputerAgentException(status, endpoint,
                    "HTTP " + status + " from " + endpoint + ": " + preview, null);
        }
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    private String toJson(Object value, String endpoint) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ComputerAgentException(-1, endpoint, "Failed to serialize request: " + ex.getMessage(), ex);
        }
    }

    private <T> T parse(String body, Class<T> type, String endpoint) {
        try {
            return mapper.readValue(body, type);
        } catch (Exception ex) {
            throw new ComputerAgentException(200, endpoint,
                    "Failed to parse response as " + type.getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    private JsonNode parseTree(String body, String endpoint) {
        try {
            return mapper.readTree(body);
        } catch (Exception ex) {
            throw new ComputerAgentException(200, endpoint, "Failed to parse JSON response: " + ex.getMessage(), ex);
        }
    }
}
