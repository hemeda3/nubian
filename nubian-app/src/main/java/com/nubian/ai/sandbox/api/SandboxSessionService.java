package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import com.nubian.ai.sandbox.model.SandboxSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Async lifecycle API for sandbox sessions.
 */
public interface SandboxSessionService extends SandboxCapability {

    @Override
    default SandboxCapabilityType type() {
        return SandboxCapabilityType.SESSION;
    }

    CompletableFuture<SandboxSession> createSession(Map<String, String> labels, Map<String, String> metadata);

    CompletableFuture<Optional<SandboxSession>> getSession(String sessionId);

    CompletableFuture<List<SandboxSession>> listSessions(Map<String, String> labels);

    default CompletableFuture<List<SandboxSession>> listSessions() {
        return listSessions(Map.of());
    }

    CompletableFuture<SandboxSession> startSession(String sessionId);

    CompletableFuture<SandboxSession> stopSession(String sessionId);

    CompletableFuture<Void> deleteSession(String sessionId);
}
