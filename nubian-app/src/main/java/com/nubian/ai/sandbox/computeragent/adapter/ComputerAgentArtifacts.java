package com.nubian.ai.sandbox.computeragent.adapter;

import com.nubian.ai.sandbox.api.SandboxArtifacts;
import com.nubian.ai.sandbox.computeragent.ComputerAgentClient;
import com.nubian.ai.sandbox.computeragent.ComputerAgentException;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.FileEntry;
import com.nubian.ai.sandbox.computeragent.ComputerAgentSandboxException;
import com.nubian.ai.sandbox.model.SandboxArtifact;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SandboxArtifacts} adapter that discovers artifacts by listing the
 * well-known root paths ({@code /workspace}, {@code /downloads}, {@code /uploads})
 * on the Ubuntu-desktop guest via {@link ComputerAgentClient}.
 *
 * <p>The in-memory artifact registry mirrors the pattern used by
 * {@code FirecrackerSandboxArtifacts}: artifacts are stored per-session in a
 * concurrent map and surfaced through {@link #listArtifacts(String)}.
 * No Spring, no Lombok — pure POJO.
 */
public class ComputerAgentArtifacts implements SandboxArtifacts {

    private static final Logger log = LoggerFactory.getLogger(ComputerAgentArtifacts.class);

    /** Root directories scanned when listing artifacts — mirrors FirecrackerSandboxArtifacts. */
    private static final List<String> ARTIFACT_ROOTS = List.of("/workspace", "/downloads", "/uploads");

    private final String providerId;
    private final ComputerAgentClient client;

    /** Per-session artifact registry: sessionId → (artifactId → artifact). */
    private final ConcurrentMap<String, ConcurrentMap<String, SandboxArtifact>> artifacts =
            new ConcurrentHashMap<>();

    public ComputerAgentArtifacts(String providerId, ComputerAgentClient client) {
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
                "runtime", "ubuntu-desktop",
                "implementation", "computer-agent-file-artifacts");
    }

    // -------------------------------------------------------------------------
    // SandboxArtifacts
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<SandboxArtifact> createArtifact(String sessionId, SandboxArtifact artifact) {
        if (artifact == null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(
                    artifactFailure(sessionId, "artifacts.createArtifact", "artifact is required")));
        }
        if (isBlank(sessionId)) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(
                    artifactFailure(sessionId, "artifacts.createArtifact", "sessionId is required")));
        }
        SandboxArtifact saved = normalize(sessionId, artifact, artifact.sizeBytes(), artifact.mediaType());
        artifacts.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                .put(saved.artifactId(), saved);
        return CompletableFuture.completedFuture(saved);
    }

    @Override
    public CompletableFuture<Optional<SandboxArtifact>> getArtifact(String sessionId, String artifactId) {
        if (isBlank(sessionId)) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(
                    artifactFailure(sessionId, "artifacts.getArtifact", "sessionId is required")));
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(
                artifacts.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(artifactId)));
    }

    /**
     * Lists all registered artifacts for the session, then additionally discovers
     * any files under {@link #ARTIFACT_ROOTS} via {@link ComputerAgentClient#listFiles(String)}.
     * Files found on the guest but not yet registered are auto-registered and included
     * in the returned list — same pattern as {@code FirecrackerSandboxArtifacts}.
     */
    @Override
    public CompletableFuture<List<SandboxArtifact>> listArtifacts(String sessionId) {
        if (isBlank(sessionId)) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(
                    artifactFailure(sessionId, "artifacts.listArtifacts", "sessionId is required")));
        }
        return CompletableFuture.supplyAsync(() -> {
            ConcurrentMap<String, SandboxArtifact> sessionMap =
                    artifacts.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>());

            for (String root : ARTIFACT_ROOTS) {
                try {
                    List<FileEntry> entries = client.listFiles(root);
                    if (entries == null) {
                        continue;
                    }
                    for (FileEntry entry : entries) {
                        String entryPath = entry.path();
                        if (entryPath == null || entryPath.isBlank()) {
                            continue;
                        }
                        boolean directory = "directory".equalsIgnoreCase(entry.type());
                        if (directory) {
                            continue; // only surface files, not directories
                        }
                        // auto-register if not already present (keyed by stable id to avoid duplicates)
                        String stableId = stableArtifactId(sessionId, entryPath);
                        sessionMap.computeIfAbsent(stableId, ignored -> {
                            String mediaType = ComputerAgentFileSystem.resolveMediaType(entryPath, false);
                            Map<String, String> meta = new LinkedHashMap<>();
                            meta.put("computer-agent.path", entryPath);
                            meta.put("computer-agent.root", root);
                            return new SandboxArtifact(
                                    providerId,
                                    sessionId,
                                    stableId,
                                    nameFromPath(entryPath, stableId),
                                    entryPath,
                                    mediaType,
                                    entry.size(),
                                    artifactUri(sessionId, entryPath, stableId),
                                    Instant.ofEpochSecond(entry.mtime() > 0 ? entry.mtime() : Instant.now().getEpochSecond()),
                                    Map.copyOf(meta));
                        });
                    }
                } catch (ComputerAgentException ex) {
                    log.debug("listArtifacts fallback: {}", ex.toString());
                    // root may not exist on this guest — skip silently, same as Firecracker pattern
                }
            }

            return sessionMap.values()
                    .stream()
                    .sorted(Comparator.comparing(SandboxArtifact::createdAt))
                    .toList();
        });
    }

    @Override
    public CompletableFuture<Void> deleteArtifact(String sessionId, String artifactId) {
        if (isBlank(sessionId)) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(
                    artifactFailure(sessionId, "artifacts.deleteArtifact", "sessionId is required")));
        }
        ConcurrentMap<String, SandboxArtifact> sessionArtifacts = artifacts.get(sessionId);
        if (sessionArtifacts == null || sessionArtifacts.remove(artifactId) == null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(new SandboxFailure(
                    providerId,
                    sessionId,
                    SandboxFailureCode.NOT_FOUND,
                    "computer-agent sandbox artifact is unknown",
                    "artifacts.deleteArtifact",
                    false,
                    Map.of())));
        }
        return CompletableFuture.completedFuture(null);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SandboxArtifact normalize(
            String sessionId,
            SandboxArtifact artifact,
            long sizeBytes,
            String mediaType) {
        String artifactId = isBlank(artifact.artifactId()) ? UUID.randomUUID().toString() : artifact.artifactId();
        String path = artifact.path();
        Map<String, String> meta = new LinkedHashMap<>(artifact.metadata());
        meta.put("computer-agent.path", path == null ? "" : path);
        return new SandboxArtifact(
                providerId,
                sessionId,
                artifactId,
                isBlank(artifact.name()) ? nameFromPath(path, artifactId) : artifact.name(),
                path,
                isBlank(mediaType) ? artifact.mediaType() : mediaType,
                sizeBytes > 0 ? sizeBytes : artifact.sizeBytes(),
                artifact.uri() == null ? artifactUri(sessionId, path, artifactId) : artifact.uri(),
                artifact.createdAt() == null ? Instant.now() : artifact.createdAt(),
                Map.copyOf(meta));
    }

    private URI artifactUri(String sessionId, String path, String artifactId) {
        String suffix = isBlank(path) ? artifactId : path;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return URI.create("computer-agent://" + providerId + "/" + sessionId + suffix);
    }

    /** Deterministic artifact id derived from session + path (avoids duplicate registrations). */
    private static String stableArtifactId(String sessionId, String path) {
        return UUID.nameUUIDFromBytes(
                (sessionId + ":" + path).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private SandboxFailure artifactFailure(String sessionId, String operation, String message) {
        return new SandboxFailure(
                providerId,
                sessionId,
                SandboxFailureCode.ARTIFACT_ERROR,
                message,
                operation,
                true,
                Map.of());
    }

    private static String nameFromPath(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
