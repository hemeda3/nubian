package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.api.SandboxArtifacts;
import com.nubian.ai.sandbox.model.SandboxArtifact;
import com.nubian.ai.sandbox.model.SandboxFile;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FirecrackerSandboxArtifacts implements SandboxArtifacts {
    private final String providerId;
    private final FirecrackerSandboxSessionService sessions;
    private final FirecrackerSandboxFileSystem fileSystem;
    private final ConcurrentMap<String, ConcurrentMap<String, SandboxArtifact>> artifacts = new ConcurrentHashMap<>();

    public FirecrackerSandboxArtifacts(
            FirecrackerSandboxSessionService sessions,
            FirecrackerSandboxFileSystem fileSystem) {
        this(FirecrackerSandboxProvider.PROVIDER_ID, sessions, fileSystem);
    }

    public FirecrackerSandboxArtifacts(
            String providerId,
            FirecrackerSandboxSessionService sessions,
            FirecrackerSandboxFileSystem fileSystem) {
        this.providerId = providerId;
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.fileSystem = java.util.Objects.requireNonNull(fileSystem, "fileSystem");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("runtime", "firecracker", "implementation", "flyvm-file-artifacts");
    }

    @Override
    public CompletableFuture<SandboxArtifact> createArtifact(String sessionId, SandboxArtifact artifact) {
        if (artifact == null) {
            return FirecrackerSandboxFailures.failedFuture(failure(sessionId, "artifacts.createArtifact", "artifact is required"));
        }
        sessions.requireRunningSession(sessionId, "artifacts.createArtifact");
        return (artifact.path().isBlank()
                ? CompletableFuture.completedFuture(normalize(sessionId, artifact, artifact.sizeBytes(), artifact.mediaType()))
                : fileSystem.readFile(sessionId, artifact.path())
                        .thenApply(file -> normalize(sessionId, artifact, file.sizeBytes(), file.mediaType())))
                .thenApply(saved -> {
                    artifacts.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                            .put(saved.artifactId(), saved);
                    return saved;
                });
    }

    @Override
    public CompletableFuture<Optional<SandboxArtifact>> getArtifact(String sessionId, String artifactId) {
        sessions.requireRunningSession(sessionId, "artifacts.getArtifact");
        return CompletableFuture.completedFuture(Optional.ofNullable(
                artifacts.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(artifactId)));
    }

    @Override
    public CompletableFuture<List<SandboxArtifact>> listArtifacts(String sessionId) {
        sessions.requireRunningSession(sessionId, "artifacts.listArtifacts");
        return CompletableFuture.completedFuture(artifacts.getOrDefault(sessionId, new ConcurrentHashMap<>())
                .values()
                .stream()
                .sorted(Comparator.comparing(SandboxArtifact::createdAt))
                .toList());
    }

    @Override
    public CompletableFuture<Void> deleteArtifact(String sessionId, String artifactId) {
        sessions.requireRunningSession(sessionId, "artifacts.deleteArtifact");
        ConcurrentMap<String, SandboxArtifact> sessionArtifacts = artifacts.get(sessionId);
        if (sessionArtifacts == null || sessionArtifacts.remove(artifactId) == null) {
            return FirecrackerSandboxFailures.failedFuture(FirecrackerSandboxFailures.notFound(
                    providerId,
                    sessionId,
                    "artifacts.deleteArtifact",
                    "Firecracker sandbox artifact is unknown"));
        }
        return CompletableFuture.completedFuture(null);
    }

    private SandboxArtifact normalize(String sessionId, SandboxArtifact artifact, long sizeBytes, String mediaType) {
        String artifactId = artifact.artifactId().isBlank() ? UUID.randomUUID().toString() : artifact.artifactId();
        String path = artifact.path();
        Map<String, String> metadata = new LinkedHashMap<>(artifact.metadata());
        metadata.put("flyvm.path", path);
        return new SandboxArtifact(
                providerId,
                sessionId,
                artifactId,
                artifact.name().isBlank() ? nameFromPath(path, artifactId) : artifact.name(),
                path,
                mediaType == null || mediaType.isBlank() ? artifact.mediaType() : mediaType,
                sizeBytes > 0 ? sizeBytes : artifact.sizeBytes(),
                artifact.uri() == null ? artifactUri(sessionId, path, artifactId) : artifact.uri(),
                artifact.createdAt() == null ? Instant.now() : artifact.createdAt(),
                metadata);
    }

    private URI artifactUri(String sessionId, String path, String artifactId) {
        String suffix = path == null || path.isBlank() ? artifactId : path;
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return URI.create("firecracker://" + providerId + "/" + sessionId + suffix);
    }

    private SandboxFailure failure(String sessionId, String operation, String message) {
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
}
