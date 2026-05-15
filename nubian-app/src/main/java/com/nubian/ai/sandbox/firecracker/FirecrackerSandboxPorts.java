package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.api.SandboxPorts;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxPort;
import com.nubian.ai.sandbox.model.SandboxSession;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class FirecrackerSandboxPorts implements SandboxPorts {
    private final String providerId;
    private final FirecrackerSandboxSessionService sessions;

    public FirecrackerSandboxPorts(FirecrackerSandboxSessionService sessions) {
        this(FirecrackerSandboxProvider.PROVIDER_ID, sessions);
    }

    public FirecrackerSandboxPorts(String providerId, FirecrackerSandboxSessionService sessions) {
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
                "implementation", "flyvm-rest-proxy",
                "runtime", "firecracker");
    }

    @Override
    public CompletableFuture<SandboxPort> exposePort(
            String sessionId,
            int port,
            String protocol,
            boolean publicAccess) {

        SandboxFailure validation = validatePort(sessionId, port, "ports.exposePort");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }

        return listPorts(sessionId)
                .thenApply(ports -> ports.stream()
                        .filter(candidate -> candidate.port() == port)
                        .findFirst()
                        .orElseThrow(() -> new FirecrackerSandboxException(FirecrackerSandboxFailures.unsupported(
                                providerId,
                                sessionId,
                                "ports.exposePort",
                                "FlyVM provider only exposes fixed computer ports 5900, 6080, and 6090",
                                Map.of("port", Integer.toString(port))))));
    }

    @Override
    public CompletableFuture<List<SandboxPort>> listPorts(String sessionId) {
        SandboxFailure validation = sessions.validateKnownSession(sessionId, "ports.listPorts");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }

        SandboxSession session = sessions.requireRunningSession(sessionId, "ports.listPorts").orElseThrow();
        Map<String, String> metadata = session.metadata();
        return CompletableFuture.completedFuture(List.of(
                port(sessionId, 6080, "http", metadata.get("flyvm.noVncUrl"), false, metadata),
                port(sessionId, 5900, "vnc", metadata.get("flyvm.vncUrl"), false, metadata),
                port(sessionId, 6090, "http", metadata.get("flyvm.agentBaseUrl"), false, metadata)));
    }

    @Override
    public CompletableFuture<Void> closePort(String sessionId, int port) {
        SandboxFailure validation = validatePort(sessionId, port, "ports.closePort");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }

        return FirecrackerSandboxFailures.failedFuture(FirecrackerSandboxFailures.unsupported(
                providerId,
                sessionId,
                "ports.closePort",
                "FlyVM fixed computer ports are tied to the session lifecycle",
                Map.of("port", Integer.toString(port))));
    }

    private SandboxFailure validatePort(String sessionId, int port, String operation) {
        SandboxFailure validation = sessions.validateKnownSession(sessionId, operation);
        if (validation != null) {
            return validation;
        }
        if (port < 1 || port > 65535) {
            return FirecrackerSandboxFailures.validation(
                    providerId,
                    sessionId,
                    operation,
                    "Port must be between 1 and 65535");
        }
        return null;
    }

    private SandboxPort port(
            String sessionId,
            int guestPort,
            String protocol,
            String url,
            boolean publicAccess,
            Map<String, String> sessionMetadata) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("flyvm.guestHost", sessionMetadata.getOrDefault("flyvm.guestHost", ""));
        metadata.put("flyvm.endpointMode", sessionMetadata.getOrDefault("flyvm.endpointMode", "flyvm-rest-proxy"));
        metadata.put("flyvm.neverExposePublicly", Boolean.toString(guestPort == 6090));
        metadata.put("flyvm.guestPort", Integer.toString(guestPort));
        return new SandboxPort(
                providerId,
                sessionId,
                guestPort,
                protocol,
                url == null || url.isBlank() ? null : URI.create(url),
                publicAccess,
                metadata);
    }
}
