package com.nubian.ai.sandbox.computeragent;

import com.nubian.ai.sandbox.api.SandboxSessionService;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import com.nubian.ai.sandbox.model.SandboxSession;
import com.nubian.ai.sandbox.model.SandboxSessionStatus;

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
import java.util.regex.Pattern;

/**
 * In-memory {@link SandboxSessionService} for the computer-agent backend.
 *
 * <p>The backend is stateless w.r.t. the guest: there is exactly ONE guest (configured by
 * {@link ComputerAgentProperties#getHost()}). Any {@code sessionId} passed by the agent maps to
 * the same physical machine. {@code createSession} records the session in memory as
 * {@link SandboxSessionStatus#RUNNING} immediately; {@code start}/{@code stop} update the
 * in-memory status only; {@code delete} removes the entry.
 */
public class ComputerAgentSandboxSessionService implements SandboxSessionService {

    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final String providerId;
    private final ComputerAgentEndpoints endpoints;
    private final ConcurrentMap<String, SandboxSession> sessions = new ConcurrentHashMap<>();

    public ComputerAgentSandboxSessionService(String providerId, ComputerAgentEndpoints endpoints) {
        if (isBlank(providerId)) {
            throw new IllegalArgumentException("providerId is required");
        }
        this.providerId = providerId;
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "provider.id", providerId,
                "implementation", "computer-agent-http",
                "sessionModel", "single-guest");
    }

    @Override
    public CompletableFuture<SandboxSession> createSession(
            Map<String, String> labels,
            Map<String, String> metadata) {

        Map<String, String> safeLabels = copy(labels);
        Map<String, String> safeMetadata = copy(metadata);

        // Detect explicit but blank session ID — treat as validation error.
        for (String key : List.of("sessionId", "sandbox.sessionId", "computer-agent.sessionId")) {
            String explicit = safeMetadata.get(key);
            if (explicit != null && explicit.isBlank()) {
                return failedFuture(new SandboxFailure(providerId, "", SandboxFailureCode.VALIDATION_ERROR,
                        "Session id is required", "sessions.createSession", false, Map.of()));
            }
        }

        String sessionId = requestedSessionId(safeMetadata)
                .orElseGet(() -> "ca-" + UUID.randomUUID());

        SandboxFailure validation = validateSessionId(sessionId, "sessions.createSession");
        if (validation != null) {
            return failedFuture(validation);
        }

        if (sessions.containsKey(sessionId)) {
            return failedFuture(conflict(sessionId, "sessions.createSession",
                    "Computer-agent sandbox session already exists"));
        }

        Instant now = Instant.now();
        SandboxSession session = new SandboxSession(
                providerId,
                sessionId,
                SandboxSessionStatus.RUNNING,
                now,
                now,
                safeLabels,
                buildMetadata(safeMetadata),
                Optional.empty());
        sessions.put(sessionId, session);
        return CompletableFuture.completedFuture(session);
    }

    @Override
    public CompletableFuture<Optional<SandboxSession>> getSession(String sessionId) {
        if (isBlank(sessionId)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(sessions.get(sessionId)));
    }

    @Override
    public CompletableFuture<List<SandboxSession>> listSessions(Map<String, String> labels) {
        Map<String, String> safeLabels = copy(labels);
        List<SandboxSession> matching = sessions.values().stream()
                .filter(session -> matchesLabels(session, safeLabels))
                .sorted(Comparator.comparing(SandboxSession::createdAt))
                .toList();
        return CompletableFuture.completedFuture(matching);
    }

    @Override
    public CompletableFuture<SandboxSession> startSession(String sessionId) {
        SandboxFailure validation = validateKnownSession(sessionId, "sessions.startSession");
        if (validation != null) {
            return failedFuture(validation);
        }

        SandboxSession existing = sessions.get(sessionId);
        if (existing.status() == SandboxSessionStatus.RUNNING) {
            return CompletableFuture.completedFuture(existing);
        }
        SandboxSession started = withStatus(existing, SandboxSessionStatus.RUNNING);
        sessions.put(sessionId, started);
        return CompletableFuture.completedFuture(started);
    }

    @Override
    public CompletableFuture<SandboxSession> stopSession(String sessionId) {
        SandboxFailure validation = validateKnownSession(sessionId, "sessions.stopSession");
        if (validation != null) {
            return failedFuture(validation);
        }

        SandboxSession existing = sessions.get(sessionId);
        if (existing.status() == SandboxSessionStatus.STOPPED) {
            return CompletableFuture.completedFuture(existing);
        }
        SandboxSession stopped = withStatus(existing, SandboxSessionStatus.STOPPED);
        sessions.put(sessionId, stopped);
        return CompletableFuture.completedFuture(stopped);
    }

    @Override
    public CompletableFuture<Void> deleteSession(String sessionId) {
        SandboxFailure validation = validateKnownSession(sessionId, "sessions.deleteSession");
        if (validation != null) {
            return failedFuture(validation);
        }
        sessions.remove(sessionId);
        return CompletableFuture.completedFuture(null);
    }

    // -------------------------------------------------------------------------
    // Package-visible helpers used by Display and Ports adapters
    // -------------------------------------------------------------------------

    /**
     * Returns the running session or throws a {@link ComputerAgentSandboxException} — mirrors
     * the pattern in {@code FirecrackerSandboxSessionService.requireRunningSession}.
     */
    Optional<SandboxSession> requireRunningSession(String sessionId, String operation) {
        SandboxFailure validation = validateKnownSession(sessionId, operation);
        if (validation != null) {
            throw new ComputerAgentSandboxException(validation);
        }
        SandboxSession session = sessions.get(sessionId);
        if (session.status() != SandboxSessionStatus.RUNNING) {
            throw new ComputerAgentSandboxException(new SandboxFailure(
                    providerId,
                    sessionId,
                    SandboxFailureCode.SESSION_ERROR,
                    "Computer-agent sandbox session is not running",
                    operation,
                    true,
                    Map.of("status", session.status().name())));
        }
        return Optional.of(session);
    }

    SandboxFailure validateKnownSession(String sessionId, String operation) {
        SandboxFailure validation = validateSessionId(sessionId, operation);
        if (validation != null) {
            return validation;
        }
        if (!sessions.containsKey(sessionId)) {
            return notFound(sessionId, operation, "Computer-agent sandbox session was not found");
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Map<String, String> buildMetadata(Map<String, String> caller) {
        Map<String, String> enriched = new LinkedHashMap<>(caller);
        enriched.put("computer-agent.host", endpoints.host());
        enriched.put("computer-agent.agentBaseUrl", endpoints.agentBaseUrl());
        enriched.put("computer-agent.vncUrl", endpoints.vncUrl());
        enriched.put("computer-agent.novncUrl", endpoints.novncUrl());
        enriched.put("computer-agent.cdpBaseUrl", endpoints.cdpBaseUrl());
        enriched.put("provider.id", providerId);
        // Aliases the existing demo frontend (and DemoSessionResponse) reads
        // under the compatibility {@code flyvm.*} prefix. Populating these makes the
        // noVNC viewer / vmId / agentBaseUrl panels work for any backend behind
        // the universal computer-agent contract without touching the frontend.
        enriched.put("flyvm.vmId", endpoints.host());
        enriched.put("flyvm.noVncUrl", endpoints.novncUrl());
        enriched.put("flyvm.vncUrl", endpoints.vncUrl());
        enriched.put("flyvm.agentBaseUrl", endpoints.agentBaseUrl());
        return Map.copyOf(enriched);
    }

    private SandboxSession withStatus(SandboxSession session, SandboxSessionStatus status) {
        return new SandboxSession(
                providerId,
                session.sessionId(),
                status,
                session.createdAt(),
                Instant.now(),
                session.labels(),
                session.metadata(),
                Optional.empty());
    }

    private SandboxFailure validateSessionId(String sessionId, String operation) {
        if (isBlank(sessionId)) {
            return new SandboxFailure(providerId, "", SandboxFailureCode.VALIDATION_ERROR,
                    "Session id is required", operation, false, Map.of());
        }
        if (!SAFE_SESSION_ID.matcher(sessionId).matches()
                || ".".equals(sessionId) || "..".equals(sessionId)) {
            return new SandboxFailure(providerId, sessionId, SandboxFailureCode.VALIDATION_ERROR,
                    "Session id may only contain letters, numbers, dots, dashes, and underscores",
                    operation, false, Map.of());
        }
        return null;
    }

    private Optional<String> requestedSessionId(Map<String, String> metadata) {
        for (String key : List.of("sessionId", "sandbox.sessionId", "computer-agent.sessionId")) {
            String value = metadata.get(key);
            if (!isBlank(value)) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private boolean matchesLabels(SandboxSession session, Map<String, String> requestedLabels) {
        return requestedLabels.entrySet().stream()
                .allMatch(e -> e.getValue().equals(session.labels().get(e.getKey())));
    }

    private SandboxFailure conflict(String sessionId, String operation, String message) {
        return new SandboxFailure(providerId, sessionId, SandboxFailureCode.CONFLICT,
                message, operation, false, Map.of());
    }

    private SandboxFailure notFound(String sessionId, String operation, String message) {
        return new SandboxFailure(providerId, sessionId, SandboxFailureCode.NOT_FOUND,
                message, operation, false, Map.of());
    }

    private static <T> CompletableFuture<T> failedFuture(SandboxFailure failure) {
        return CompletableFuture.failedFuture(new ComputerAgentSandboxException(failure));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, String> copy(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
