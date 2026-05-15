package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Component("appSandbox")
public class Sandbox {

    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<Thread, CompletableFuture<?>> LIVE = new ConcurrentHashMap<>();

    private final String baseUrl;
    private final HttpClient http;
    private final Duration requestTimeout;

    public Sandbox(
            @Value("${nubian.sandbox.base-url:http://localhost:28006/agent}") String baseUrl,
            @Value("${nubian.sandbox.request-timeout-ms:30000}") int requestTimeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.requestTimeout = Duration.ofMillis(Math.max(1000, requestTimeoutMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        log.info("[sandbox] base-url={} timeout={}ms", this.baseUrl, requestTimeoutMs);
    }

    public record Response(boolean ok, int status, String body, byte[] bytes) {
        public Response {
            if (body == null) body = "";
        }

        public JsonNode json() {
            try {
                return MAPPER.readTree(body == null ? "{}" : body);
            } catch (Exception ex) {
                throw new SandboxException("response body is not JSON: " + truncate(body, 200), ex);
            }
        }
    }

    public static class SandboxException extends RuntimeException {
        public SandboxException(String message) { super(message); }
        public SandboxException(String message, Throwable cause) { super(message, cause); }
    }

    public record DesktopApp(String name, String desktopFile, String exec, String description) {}

    public byte[] screenshot() {
        Response r = getBytes("/eyes/screenshot");
        if (!r.ok()) {
            log.warn("[sandbox] screenshot failed: HTTP {}", r.status());
            return new byte[0];
        }
        return r.bytes() == null ? new byte[0] : r.bytes();
    }

    public List<DesktopApp> appsCatalog(int limit) {
        String path = "/apps/catalog" + (limit > 0 ? "?limit=" + limit : "");
        Response r = getString(path);
        if (!r.ok()) {
            log.warn("[sandbox] /apps/catalog failed: HTTP {}", r.status());
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(r.body());
            JsonNode arr = root.path("apps");
            if (!arr.isArray()) arr = root;
            List<DesktopApp> out = new ArrayList<>();
            for (JsonNode app : arr) {
                out.add(new DesktopApp(
                        app.path("name").asText(""),
                        app.path("desktop_file").asText(""),
                        app.path("exec").asText(""),
                        app.path("description").asText("")));
            }
            return out;
        } catch (Exception ex) {
            log.warn("[sandbox] /apps/catalog parse failed: {}", ex.toString());
            return List.of();
        }
    }

    public Response launchApp(String name) {
        if (name != null && name.trim().endsWith(".desktop")) {
            return launchDesktopApp(null, name.trim(), null);
        }
        return launchDesktopApp(name, null, null);
    }

    public Response launchDesktopApp(String name, String desktopFile, String exec) {
        ObjectNode body = MAPPER.createObjectNode();
        if (name != null && !name.isBlank()) body.put("name", name);
        if (desktopFile != null && !desktopFile.isBlank()) body.put("desktop_file", desktopFile);
        if (exec != null && !exec.isBlank()) body.put("exec", exec);
        body.put("fullscreen", false);
        body.put("maximize", false);
        body.put("force_maximize", false);
        return postJson("/apps/launch", body);
    }

    public Response activateWindow(String name, String title, String xid) {
        ObjectNode body = MAPPER.createObjectNode();
        if (name != null && !name.isBlank()) body.put("name", name);
        if (title != null && !title.isBlank()) body.put("title", title);
        if (xid != null && !xid.isBlank()) body.put("xid", xid);
        body.put("fullscreen", false);
        body.put("maximize", false);
        body.put("force_maximize", false);
        return postJson("/windows/activate", body);
    }

    public Response hands(String type, Map<String, Object> args) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("type", type);
        if (args != null) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof Number n) body.put(e.getKey(), n.doubleValue());
                else if (v instanceof Boolean b) body.put(e.getKey(), b);
                else if (v instanceof Iterable<?> it) body.set(e.getKey(), MAPPER.valueToTree(it));
                else if (v.getClass().isArray()) body.set(e.getKey(), MAPPER.valueToTree(v));
                else body.put(e.getKey(), v.toString());
            }
        }
        return postJson("/hands/action", body);
    }

    public Response shell(String script) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("script", script);
        return postJson("/shell/exec", body);
    }

    public Response writeFile(String path, String content) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("path", path);
        body.put("content", content);
        return postJson("/memory/files", body);
    }

    public Response readFile(String path) {
        return getString("/memory/files?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8)
                + "&format=text");
    }

    public Response clipboardText() {
        return getString("/memory/clipboard?max_chars=4000");
    }

    public Response filesList(String path) {
        return getString("/memory/files/list?path="
                + URLEncoder.encode(path == null ? "/workspace" : path, StandardCharsets.UTF_8));
    }

    public Response fileStat(String path) {
        return getString("/memory/files/stat?path="
                + URLEncoder.encode(path == null ? "" : path, StandardCharsets.UTF_8));
    }

    /** Multi-channel verifier evidence bundle: screen geometry, active/running
     *  windows, processes, directory listings, file stats, recent files, and
     *  clipboard text when native OS access is available. Raw JSON returned to
     *  the caller and forwarded to the verifier as text evidence.
     *  <p>The actual sandbox endpoint is {@code /eyes/observe}. A previous version
     *  of this method called {@code /eyes/evidence}, which 404s — the verifier
     *  silently saw an empty bundle and concluded "no running windows" even when
     *  Chrome was visibly displaying content. */
    public Response evidenceBundle(String dirsCsv, String filesCsv) {
        StringBuilder qs = new StringBuilder("/eyes/observe?clipboard=true");
        if (dirsCsv != null && !dirsCsv.isBlank()) {
            qs.append("&dir=").append(URLEncoder.encode(dirsCsv, StandardCharsets.UTF_8));
        }
        if (filesCsv != null && !filesCsv.isBlank()) {
            qs.append("&file=").append(URLEncoder.encode(filesCsv, StandardCharsets.UTF_8));
        }
        return getString(qs.toString());
    }

    public static void abortForThread(Thread target) {
        if (target == null) {
            log.warn("[sandbox.abort] no target thread supplied");
            return;
        }
        CompletableFuture<?> future = LIVE.remove(target);
        if (future == null) {
            log.warn("[sandbox.abort] no in-flight sandbox request for thread {}", target.getName());
            return;
        }
        boolean cancelled = future.cancel(true);
        log.warn("[sandbox.abort] cancel sandbox request for thread {} -> {}", target.getName(), cancelled);
    }

    private Response getString(String path) {
        try {
            HttpResponse<String> resp = send(buildGet(path), HttpResponse.BodyHandlers.ofString(), path);
            return new Response(resp.statusCode() < 400, resp.statusCode(), resp.body(), null);
        } catch (java.io.InterruptedIOException ix) {
            throw new RuntimeException(new InterruptedException("sandbox " + path + " aborted: " + ix.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedException("sandbox " + path + " interrupted"));
        } catch (IOException ex) {
            log.warn("[sandbox] GET {} failed: {}", path, ex.toString());
            return new Response(false, -1, "transport error: " + ex.getMessage(), null);
        }
    }

    private Response getBytes(String path) {
        try {
            HttpResponse<byte[]> resp = send(buildGet(path), HttpResponse.BodyHandlers.ofByteArray(), path);
            byte[] body = resp.body() == null ? new byte[0] : resp.body();
            String text = (resp.statusCode() < 400 || body.length == 0) ? "" : new String(body, StandardCharsets.UTF_8);
            return new Response(resp.statusCode() < 400, resp.statusCode(), text, body);
        } catch (java.io.InterruptedIOException ix) {
            throw new RuntimeException(new InterruptedException("sandbox " + path + " aborted: " + ix.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedException("sandbox " + path + " interrupted"));
        } catch (IOException ex) {
            log.warn("[sandbox] GET {} bytes failed: {}", path, ex.toString());
            return new Response(false, -1, "transport error: " + ex.getMessage(), null);
        }
    }

    private Response postJson(String path, ObjectNode body) {
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception ex) {
            throw new SandboxException("could not serialize body for " + path, ex);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString(), path);
            return new Response(resp.statusCode() < 400, resp.statusCode(), resp.body(), null);
        } catch (java.io.InterruptedIOException ix) {
            throw new RuntimeException(new InterruptedException("sandbox " + path + " aborted: " + ix.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedException("sandbox " + path + " interrupted"));
        } catch (IOException ex) {
            log.warn("[sandbox] POST {} failed: {}", path, ex.toString());
            return new Response(false, -1, "transport error: " + ex.getMessage(), null);
        }
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler, String path)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> future = http.sendAsync(req, handler);
        LIVE.put(Thread.currentThread(), future);
        try {
            return future.get();
        } catch (InterruptedException ex) {
            future.cancel(true);
            throw ex;
        } catch (CancellationException ex) {
            throw new InterruptedException("sandbox " + path + " cancelled");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException ix) throw ix;
            if (cause instanceof RuntimeException rt) throw rt;
            throw new IOException("sandbox " + path + " failed: " + cause, cause);
        } finally {
            LIVE.remove(Thread.currentThread(), future);
        }
    }

    private HttpRequest buildGet(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
