package com.nubian.ai.sandbox.synthesis;

import com.nubian.ai.sandbox.model.SandboxSession;
import com.nubian.ai.sandbox.model.SandboxSessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record SandboxRuntimeSnapshot(
        String providerId,
        List<SandboxSession> sessions,
        Map<SandboxSessionStatus, Long> statusCounts,
        Map<String, Long> eventTypeCounts,
        Instant generatedAt) {
    public SandboxRuntimeSnapshot(
            String providerId,
            List<SandboxSession> sessions,
            Map<SandboxSessionStatus, Long> statusCounts,
            Instant generatedAt) {
        this(providerId, sessions, statusCounts, Map.of(), generatedAt);
    }

    public SandboxRuntimeSnapshot {
        providerId = providerId == null ? "" : providerId;
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        statusCounts = statusCounts == null
                ? sessions.stream().collect(Collectors.groupingBy(
                        SandboxSession::status,
                        java.util.LinkedHashMap::new,
                        Collectors.counting()))
                : Map.copyOf(statusCounts);
        eventTypeCounts = eventTypeCounts == null ? Map.of() : Map.copyOf(eventTypeCounts);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    public long totalSessions() {
        return sessions.size();
    }

    public long activeSessions() {
        return sessions.stream()
                .filter(session -> session.status().active())
                .count();
    }

    public long terminalSessions() {
        return sessions.stream()
                .filter(session -> session.status().terminal())
                .count();
    }

    public boolean hasActiveSessions() {
        return activeSessions() > 0;
    }

    public List<SandboxSession> sessions(SandboxSessionStatus status) {
        Objects.requireNonNull(status, "status is required");
        return sessions.stream()
                .filter(session -> session.status() == status)
                .toList();
    }
}
