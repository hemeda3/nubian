package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxArtifact;
import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Async artifact API scoped to a sandbox session.
 */
public interface SandboxArtifacts extends SandboxCapability {

    @Override
    default SandboxCapabilityType type() {
        return SandboxCapabilityType.ARTIFACTS;
    }

    CompletableFuture<SandboxArtifact> createArtifact(String sessionId, SandboxArtifact artifact);

    CompletableFuture<Optional<SandboxArtifact>> getArtifact(String sessionId, String artifactId);

    CompletableFuture<List<SandboxArtifact>> listArtifacts(String sessionId);

    CompletableFuture<Void> deleteArtifact(String sessionId, String artifactId);
}
