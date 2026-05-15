package com.nubian.ai.sandbox.computeragent.adapter;

import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.computeragent.ComputerAgentClient;
import com.nubian.ai.sandbox.computeragent.ComputerAgentException;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.FileBlob;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.FileEntry;
import com.nubian.ai.sandbox.computeragent.ComputerAgentSandboxException;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import com.nubian.ai.sandbox.model.SandboxFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link SandboxFileSystem} adapter that proxies file operations to the
 * Ubuntu-desktop guest agent via {@link ComputerAgentClient}.
 *
 * <ul>
 *   <li>{@code listFiles}  → {@code GET /memory/files/list?path=...}</li>
 *   <li>{@code readFile}   → {@code GET /memory/files?path=...}</li>
 *   <li>{@code writeFile}  → {@code POST /memory/files}</li>
 *   <li>{@code createDirectory} / {@code deletePath} — unsupported, returns a failed future</li>
 * </ul>
 *
 * <p>No Spring, no Lombok — pure POJO.
 */
public class ComputerAgentFileSystem implements SandboxFileSystem {

    private final String providerId;
    private final ComputerAgentClient client;

    public ComputerAgentFileSystem(String providerId, ComputerAgentClient client) {
        if (isBlank(providerId)) {
            throw new IllegalArgumentException("providerId is required");
        }
        this.providerId = providerId;
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "implementation", "computer-agent-guest-api",
                "runtime", "ubuntu-desktop");
    }

    // -------------------------------------------------------------------------
    // SandboxFileSystem
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<SandboxFile> readFile(String sessionId, String path) {
        SandboxFailure validation = validatePath(sessionId, path, "fileSystem.readFile");
        if (validation != null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(validation));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                FileBlob blob = client.readFile(path);
                byte[] content = blob.content() == null ? new byte[0] : blob.content();
                String mediaType = blob.contentType();
                if (isBlank(mediaType)) {
                    mediaType = resolveMediaType(path, false);
                }
                return new SandboxFile(
                        path,
                        false,
                        content.length,
                        Instant.now(),
                        mediaType,
                        content,
                        Map.of("computer-agent.path", path));
            } catch (ComputerAgentException ex) {
                throw fileApiFailure(sessionId, "fileSystem.readFile", path, ex.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<SandboxFile> writeFile(String sessionId, SandboxFile file) {
        if (file == null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(new SandboxFailure(
                    providerId, sessionId, SandboxFailureCode.VALIDATION_ERROR,
                    "File is required", "fileSystem.writeFile", false, Map.of())));
        }
        SandboxFailure validation = validatePath(sessionId, file.path(), "fileSystem.writeFile");
        if (validation != null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(validation));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] content = file.content() == null ? new byte[0] : file.content();
                client.writeFile(file.path(), content);
                return new SandboxFile(
                        file.path(),
                        false,
                        content.length,
                        Instant.now(),
                        isBlank(file.mediaType()) ? resolveMediaType(file.path(), false) : file.mediaType(),
                        content,
                        Map.of("computer-agent.path", file.path()));
            } catch (ComputerAgentException ex) {
                throw fileApiFailure(sessionId, "fileSystem.writeFile", file.path(), ex.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<List<SandboxFile>> listFiles(String sessionId, String path) {
        SandboxFailure validation = validatePath(sessionId, path, "fileSystem.listFiles");
        if (validation != null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(validation));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<FileEntry> entries = client.listFiles(path);
                List<SandboxFile> files = new ArrayList<>();
                if (entries != null) {
                    for (FileEntry entry : entries) {
                        String entryPath = entry.path();
                        if (entryPath == null || entryPath.isBlank()) {
                            continue;
                        }
                        boolean directory = "directory".equalsIgnoreCase(entry.type());
                        files.add(new SandboxFile(
                                entryPath,
                                directory,
                                entry.size(),
                                Instant.ofEpochSecond(entry.mtime()),
                                resolveMediaType(entryPath, directory),
                                new byte[0],
                                Map.of("computer-agent.path", entryPath)));
                    }
                }
                return List.copyOf(files);
            } catch (ComputerAgentException ex) {
                throw fileApiFailure(sessionId, "fileSystem.listFiles", path, ex.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Void> createDirectory(String sessionId, String path) {
        SandboxFailure validation = validatePath(sessionId, path, "fileSystem.createDirectory");
        if (validation != null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(validation));
        }
        return CompletableFuture.failedFuture(new ComputerAgentSandboxException(new SandboxFailure(
                providerId,
                sessionId,
                SandboxFailureCode.UNSUPPORTED_CAPABILITY,
                "computer-agent guest agent does not expose directory creation",
                "fileSystem.createDirectory",
                false,
                Map.of("path", path))));
    }

    @Override
    public CompletableFuture<Void> deletePath(String sessionId, String path) {
        SandboxFailure validation = validatePath(sessionId, path, "fileSystem.deletePath");
        if (validation != null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(validation));
        }
        return CompletableFuture.failedFuture(new ComputerAgentSandboxException(new SandboxFailure(
                providerId,
                sessionId,
                SandboxFailureCode.UNSUPPORTED_CAPABILITY,
                "computer-agent guest agent does not expose path deletion",
                "fileSystem.deletePath",
                false,
                Map.of("path", path))));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SandboxFailure validatePath(String sessionId, String path, String operation) {
        if (isBlank(sessionId)) {
            return new SandboxFailure(
                    providerId, sessionId, SandboxFailureCode.VALIDATION_ERROR,
                    "Session id is required", operation, false, Map.of());
        }
        if (isBlank(path)) {
            return new SandboxFailure(
                    providerId, sessionId, SandboxFailureCode.VALIDATION_ERROR,
                    "Path is required", operation, false, Map.of());
        }
        return null;
    }

    private ComputerAgentSandboxException fileApiFailure(
            String sessionId,
            String operation,
            String path,
            String detail) {
        String message = "computer-agent file API failure path=" + path
                + (detail == null || detail.isBlank() ? "" : " detail=" + detail);
        return new ComputerAgentSandboxException(new SandboxFailure(
                providerId,
                sessionId,
                SandboxFailureCode.FILE_SYSTEM_ERROR,
                message,
                operation,
                true,
                Map.of(
                        "computer-agent.path", path == null ? "" : path,
                        "computer-agent.detail", detail == null ? "" : detail)));
    }

    static String resolveMediaType(String path, boolean directory) {
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
