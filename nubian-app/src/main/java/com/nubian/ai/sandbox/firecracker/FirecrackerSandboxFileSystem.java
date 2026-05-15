package com.nubian.ai.sandbox.firecracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxCommandResult;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import com.nubian.ai.sandbox.model.SandboxFile;
import com.nubian.ai.sandbox.model.SandboxSession;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirecrackerSandboxFileSystem implements SandboxFileSystem {
    private static final Logger log = LoggerFactory.getLogger(FirecrackerSandboxFileSystem.class);

    private final String providerId;
    private final FirecrackerSandboxSessionService sessions;

    public FirecrackerSandboxFileSystem(FirecrackerSandboxSessionService sessions) {
        this(FirecrackerSandboxProvider.PROVIDER_ID, sessions);
    }

    public FirecrackerSandboxFileSystem(String providerId, FirecrackerSandboxSessionService sessions) {
        if (FirecrackerSandboxFailures.isBlank(providerId)) {
            throw new IllegalArgumentException("providerId is required");
        }
        this.providerId = providerId;
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "implementation", "flyvm-guest-api",
                "runtime", "firecracker");
    }

    @Override
    public CompletableFuture<SandboxFile> readFile(String sessionId, String path) {
        SandboxFailure validation = validatePath(sessionId, path, "fileSystem.readFile");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }
        return CompletableFuture.supplyAsync(() -> {
            SandboxSession session = runningSession(sessionId, "fileSystem.readFile");
            return readViaFilesApi(sessionId, session.metadata().get("flyvm.agentBaseUrl"), path);
        });
    }

    @Override
    public CompletableFuture<SandboxFile> writeFile(String sessionId, SandboxFile file) {
        if (file == null) {
            return FirecrackerSandboxFailures.failedFuture(FirecrackerSandboxFailures.validation(
                    providerId,
                    sessionId,
                    "fileSystem.writeFile",
                    "File is required"));
        }
        SandboxFailure validation = validatePath(sessionId, file.path(), "fileSystem.writeFile");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }
        return CompletableFuture.supplyAsync(() -> {
            String encoded = Base64.getEncoder().encodeToString(file.content());
            String script = "p=" + FlyVmComputerClient.shellQuote(file.path()) + "\n"
                    + "mkdir -p -- \"$(dirname -- \"$p\")\"\n"
                    + "base64 -d > \"$p\" <<'NUBIAN_FILE_EOF'\n"
                    + encoded + "\n"
                    + "NUBIAN_FILE_EOF\n"
                    + "chmod a+rw \"$p\" || true\n"
                    + "printf 'FILE\\t%s\\t%s\\t%s\\n' \"$(stat -c %s \"$p\")\" \"$(stat -c %Y \"$p\")\" \"$p\"";
            SandboxCommandResult result = execute(sessionId, script, "/workspace", "fileSystem.writeFile");
            ensureSuccess(result, "fileSystem.writeFile");
            return parseMetadata(file.path(), result.stdout(), file.mediaType(), file.content());
        });
    }

    @Override
    public CompletableFuture<List<SandboxFile>> listFiles(String sessionId, String path) {
        SandboxFailure validation = validatePath(sessionId, path, "fileSystem.listFiles");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }
        return CompletableFuture.supplyAsync(() -> {
            SandboxSession session = runningSession(sessionId, "fileSystem.listFiles");
            return listViaFilesApi(sessionId, session.metadata().get("flyvm.agentBaseUrl"), path);
        });
    }

    @Override
    public CompletableFuture<Void> createDirectory(String sessionId, String path) {
        SandboxFailure validation = validatePath(sessionId, path, "fileSystem.createDirectory");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }
        return CompletableFuture.runAsync(() -> {
            SandboxCommandResult result = execute(
                    sessionId,
                    "mkdir -p -- " + FlyVmComputerClient.shellQuote(path),
                    "/workspace",
                    "fileSystem.createDirectory");
            ensureSuccess(result, "fileSystem.createDirectory");
        });
    }

    @Override
    public CompletableFuture<Void> deletePath(String sessionId, String path) {
        SandboxFailure validation = validatePath(sessionId, path, "fileSystem.deletePath");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }
        return CompletableFuture.runAsync(() -> {
            SandboxCommandResult result = execute(
                    sessionId,
                    "rm -rf -- " + FlyVmComputerClient.shellQuote(path),
                    "/workspace",
                    "fileSystem.deletePath");
            ensureSuccess(result, "fileSystem.deletePath");
        });
    }

    private SandboxFailure validatePath(String sessionId, String path, String operation) {
        SandboxFailure validation = sessions.validateKnownSession(sessionId, operation);
        if (validation != null) {
            return validation;
        }
        if (FirecrackerSandboxFailures.isBlank(path)) {
            return FirecrackerSandboxFailures.validation(
                    providerId,
                    sessionId,
                    operation,
                    "Path is required");
        }
        return null;
    }

    private SandboxCommandResult execute(String sessionId, String script, String cwd, String operation) {
        return sessions.flyVmClient().execute(
                sessionId,
                runningSession(sessionId, operation).metadata().get("flyvm.agentBaseUrl"),
                new SandboxCommand(
                        script,
                        List.of(),
                        cwd,
                        Map.of(),
                        java.time.Duration.ofSeconds(120),
                        false,
                        Map.of("sandbox.operation", operation)));
    }

    private SandboxSession runningSession(String sessionId, String operation) {
        return sessions.requireRunningSession(sessionId, operation).orElseThrow();
    }

    private SandboxFile readViaFilesApi(String sessionId, String baseUrl, String path) {
        HttpResponse<byte[]> response = sessions.flyVmClient().guestFile(baseUrl, path);
        if (response.statusCode() == 404 && isCollectionNotFound(responseBodyPreview(response.body()))) {
            String rebound = sessions.rediscoverAndRebind(sessionId);
            if (rebound != null && !rebound.equals(baseUrl)) {
                log.warn("FlyVM vmId rediscovered after COLLECTION_NOT_FOUND on /files; retrying once. old={} new={}",
                        baseUrl, rebound);
                response = sessions.flyVmClient().guestFile(rebound, path);
                baseUrl = rebound;
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw fileApiFailure(
                    sessionId,
                    "fileSystem.readFile",
                    "/files",
                    baseUrl,
                    path,
                    response.statusCode(),
                    responseBodyPreview(response.body()));
        }
        byte[] body = response.body() == null ? new byte[0] : response.body();
        String resolvedPath = response.headers().firstValue("X-FlyVM-Path").orElse(path);
        long size = response.headers().firstValue("X-FlyVM-Size")
                .map(v -> parseLong(v, body.length))
                .orElse((long) body.length);
        String contentType = response.headers().firstValue("Content-Type")
                .map(v -> v.split(";", 2)[0].trim())
                .filter(v -> !v.isBlank())
                .orElse(mediaType(resolvedPath, false));
        return new SandboxFile(
                resolvedPath,
                false,
                size,
                Instant.now(),
                contentType,
                body,
                Map.of(
                        "flyvm.path", resolvedPath,
                        "flyvm.fileApi", "true"));
    }

    private List<SandboxFile> listViaFilesApi(String sessionId, String baseUrl, String path) {
        JsonNode root;
        try {
            root = sessions.flyVmClient().guestFilesList(baseUrl, path);
        } catch (RuntimeException ex) {
            if (isCollectionNotFound(ex.getMessage())) {
                String rebound = sessions.rediscoverAndRebind(sessionId);
                if (rebound != null && !rebound.equals(baseUrl)) {
                    log.warn("FlyVM vmId rediscovered after COLLECTION_NOT_FOUND on /files/list; retrying once. old={} new={}",
                            baseUrl, rebound);
                    try {
                        root = sessions.flyVmClient().guestFilesList(rebound, path);
                        baseUrl = rebound;
                    } catch (RuntimeException retry) {
                        throw fileApiFailure(
                                sessionId,
                                "fileSystem.listFiles",
                                "/files/list",
                                rebound,
                                path,
                                -1,
                                retry.getMessage());
                    }
                } else {
                    throw fileApiFailure(
                            sessionId,
                            "fileSystem.listFiles",
                            "/files/list",
                            baseUrl,
                            path,
                            -1,
                            ex.getMessage());
                }
            } else {
                throw fileApiFailure(
                        sessionId,
                        "fileSystem.listFiles",
                        "/files/list",
                        baseUrl,
                        path,
                        -1,
                        ex.getMessage());
            }
        }
        JsonNode entries = root.path("entries");
        if (!entries.isArray()) {
            throw fileApiFailure(
                    sessionId,
                    "fileSystem.listFiles",
                    "/files/list",
                    baseUrl,
                    path,
                    200,
                    "response missing entries array: " + root);
        }
        List<SandboxFile> files = new ArrayList<>();
        for (JsonNode entry : entries) {
            String childPath = entry.path("path").asText("");
            if (childPath.isBlank()) {
                continue;
            }
            boolean directory = "directory".equalsIgnoreCase(entry.path("type").asText(""));
            files.add(new SandboxFile(
                    childPath,
                    directory,
                    entry.path("size").asLong(0L),
                    Instant.ofEpochSecond(entry.path("mtime").asLong(Instant.now().getEpochSecond())),
                    mediaType(childPath, directory),
                    new byte[0],
                    Map.of(
                            "flyvm.path", childPath,
                            "flyvm.fileApi", "true")));
        }
        return List.copyOf(files);
    }

    private void ensureSuccess(SandboxCommandResult result, String operation) {
        if (!result.successful()) {
            throw new FirecrackerSandboxException(new SandboxFailure(
                    providerId,
                    result.sessionId(),
                    com.nubian.ai.sandbox.model.SandboxFailureCode.FILE_SYSTEM_ERROR,
                    result.stderr().isBlank() ? "FlyVM file-system command failed" : result.stderr(),
                    operation,
                    true,
                    result.metadata()));
        }
    }

    private FirecrackerSandboxException fileApiFailure(
            String sessionId,
            String operation,
            String endpoint,
            String baseUrl,
            String path,
            int statusCode,
            String detail) {
        boolean missing = statusCode == 404;
        String message = (missing ? "FlyVM file not found" : "FlyVM file artifact API failure")
                + " " + endpoint + " path=" + path
                + (statusCode >= 0 ? " status=" + statusCode : "")
                + (detail == null || detail.isBlank() ? "" : " detail=" + detail);
        if (missing) {
            log.warn("{}", message);
        } else {
            log.error("{}", message);
        }
        throw new FirecrackerSandboxException(new SandboxFailure(
                providerId,
                sessionId,
                SandboxFailureCode.FILE_SYSTEM_ERROR,
                message,
                operation,
                !missing,
                Map.of(
                        "flyvm.agentBaseUrl", baseUrl == null ? "" : baseUrl,
                        "flyvm.fileApi.endpoint", endpoint,
                        "flyvm.fileApi.path", path,
                        "flyvm.fileApi.status", String.valueOf(statusCode),
                        "flyvm.fileApi.missing", String.valueOf(missing),
                        "flyvm.fileApi.detail", detail == null ? "" : detail)));
    }

    private static boolean isCollectionNotFound(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("collection_not_found")
                || lower.contains("computer vm not found");
    }

    private static String responseBodyPreview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() <= 1000) {
            return text;
        }
        return text.substring(0, 1000) + "...";
    }

    private SandboxFile parseRead(String path, String stdout) {
        String[] parts = stdout.split("\\R", 2);
        if (parts.length == 0) {
            return new SandboxFile(path, false, 0, Instant.now(), "application/octet-stream", new byte[0], Map.of());
        }
        String[] header = parts[0].split("\\t");
        boolean directory = header.length > 0 && "DIR".equals(header[0]);
        long size = header.length > 1 ? parseLong(header[1], 0L) : 0L;
        Instant modified = header.length > 2 ? Instant.ofEpochSecond(parseLong(header[2], Instant.now().getEpochSecond())) : Instant.now();
        byte[] content = directory || parts.length < 2
                ? new byte[0]
                : Base64.getDecoder().decode(parts[1].trim());
        return new SandboxFile(
                path,
                directory,
                directory ? 0 : content.length,
                modified,
                mediaType(path, directory),
                content,
                Map.of("flyvm.path", path));
    }

    private SandboxFile parseMetadata(String path, String stdout, String mediaType, byte[] content) {
        String[] header = stdout.lines().findFirst().orElse("").split("\\t");
        long size = header.length > 1 ? parseLong(header[1], content == null ? 0L : content.length) : content.length;
        Instant modified = header.length > 2 ? Instant.ofEpochSecond(parseLong(header[2], Instant.now().getEpochSecond())) : Instant.now();
        return new SandboxFile(
                path,
                false,
                size,
                modified,
                mediaType == null || mediaType.isBlank() ? mediaType(path, false) : mediaType,
                content,
                Map.of("flyvm.path", path));
    }

    private List<SandboxFile> parseList(String stdout) {
        List<SandboxFile> files = new ArrayList<>();
        for (String line : stdout == null ? List.<String>of() : stdout.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", 4);
            if (parts.length < 4) {
                continue;
            }
            boolean directory = "d".equals(parts[0]);
            files.add(new SandboxFile(
                    parts[3],
                    directory,
                    parseLong(parts[1], 0L),
                    Instant.ofEpochSecond((long) parseDouble(parts[2], Instant.now().getEpochSecond())),
                    mediaType(parts[3], directory),
                    new byte[0],
                    Map.of("flyvm.path", parts[3])));
        }
        return List.copyOf(files);
    }

    private static String mediaType(String path, boolean directory) {
        if (directory) {
            return "inode/directory";
        }
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (RuntimeException ex) {
            log.debug("parseLong fallback: {}", ex.toString());
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (RuntimeException ex) {
            log.debug("parseDouble fallback: {}", ex.toString());
            return fallback;
        }
    }
}
