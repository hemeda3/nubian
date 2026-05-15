package com.nubian.ai.sandbox.lifecycle;

import com.nubian.ai.sandbox.model.SandboxSession;
import com.nubian.ai.sandbox.model.SandboxSessionStatus;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SandboxStateMachine {
    private final Map<SandboxSessionStatus, Set<SandboxSessionStatus>> transitions;

    public SandboxStateMachine() {
        this(defaultTransitions());
    }

    public SandboxStateMachine(Map<SandboxSessionStatus, Set<SandboxSessionStatus>> transitions) {
        Objects.requireNonNull(transitions, "transitions is required");
        EnumMap<SandboxSessionStatus, Set<SandboxSessionStatus>> copy =
                new EnumMap<>(SandboxSessionStatus.class);
        for (SandboxSessionStatus status : SandboxSessionStatus.values()) {
            copy.put(status, EnumSet.noneOf(SandboxSessionStatus.class));
        }
        transitions.forEach((from, to) -> {
            Objects.requireNonNull(from, "transition source is required");
            EnumSet<SandboxSessionStatus> targets = EnumSet.noneOf(SandboxSessionStatus.class);
            if (to != null) {
                to.stream()
                        .filter(Objects::nonNull)
                        .forEach(targets::add);
            }
            copy.put(from, Set.copyOf(targets));
        });
        this.transitions = Map.copyOf(copy);
    }

    public Map<SandboxSessionStatus, Set<SandboxSessionStatus>> transitions() {
        return transitions;
    }

    public Set<SandboxSessionStatus> allowedTransitionsFrom(SandboxSessionStatus status) {
        if (status == null) {
            return Set.of();
        }
        return transitions.getOrDefault(status, Set.of());
    }

    public boolean canTransition(SandboxSessionStatus from, SandboxSessionStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return transitions.getOrDefault(from, Set.of()).contains(to);
    }

    public boolean canReach(SandboxSessionStatus from, SandboxSessionStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        Set<SandboxSessionStatus> visited = EnumSet.noneOf(SandboxSessionStatus.class);
        ArrayDeque<SandboxSessionStatus> pending = new ArrayDeque<>();
        pending.add(from);
        while (!pending.isEmpty()) {
            SandboxSessionStatus current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (SandboxSessionStatus next : allowedTransitionsFrom(current)) {
                if (next == to) {
                    return true;
                }
                if (!visited.contains(next)) {
                    pending.add(next);
                }
            }
        }
        return false;
    }

    public boolean canTransitionSequence(List<SandboxSessionStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return false;
        }
        List<SandboxSessionStatus> copy = new ArrayList<>(statuses);
        for (int index = 1; index < copy.size(); index++) {
            if (!canTransition(copy.get(index - 1), copy.get(index))) {
                return false;
            }
        }
        return true;
    }

    public void requireTransition(String sessionId, SandboxSessionStatus from, SandboxSessionStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "Invalid sandbox state transition for " + sessionId + ": " + from + " -> " + to);
        }
    }

    public SandboxSession transition(SandboxSession session, SandboxSessionStatus to) {
        return transition(session, to, Instant.now());
    }

    public SandboxSession transition(SandboxSession session, SandboxSessionStatus to, Instant updatedAt) {
        Objects.requireNonNull(session, "session is required");
        requireTransition(session.sessionId(), session.status(), to);
        return new SandboxSession(
                session.providerId(),
                session.sessionId(),
                to,
                session.createdAt(),
                updatedAt == null ? Instant.now() : updatedAt,
                session.labels(),
                session.metadata(),
                session.failure());
    }

    public boolean isActive(SandboxSessionStatus status) {
        return status != null && status.active();
    }

    public boolean isTerminal(SandboxSessionStatus status) {
        return status != null && status.terminal();
    }

    public static Set<SandboxSessionStatus> activeStatuses() {
        EnumSet<SandboxSessionStatus> statuses = EnumSet.noneOf(SandboxSessionStatus.class);
        for (SandboxSessionStatus status : SandboxSessionStatus.values()) {
            if (status.active()) {
                statuses.add(status);
            }
        }
        return Set.copyOf(statuses);
    }

    public static Set<SandboxSessionStatus> terminalStatuses() {
        EnumSet<SandboxSessionStatus> statuses = EnumSet.noneOf(SandboxSessionStatus.class);
        for (SandboxSessionStatus status : SandboxSessionStatus.values()) {
            if (status.terminal()) {
                statuses.add(status);
            }
        }
        return Set.copyOf(statuses);
    }

    public static Map<SandboxSessionStatus, Set<SandboxSessionStatus>> defaultTransitions() {
        EnumMap<SandboxSessionStatus, Set<SandboxSessionStatus>> transitions =
                new EnumMap<>(SandboxSessionStatus.class);
        transitions.put(SandboxSessionStatus.CREATING, EnumSet.of(
                SandboxSessionStatus.RUNNING,
                SandboxSessionStatus.STOPPED,
                SandboxSessionStatus.FAILED,
                SandboxSessionStatus.DELETING));
        transitions.put(SandboxSessionStatus.STARTING, EnumSet.of(
                SandboxSessionStatus.RUNNING,
                SandboxSessionStatus.FAILED,
                SandboxSessionStatus.STOPPING));
        transitions.put(SandboxSessionStatus.RUNNING, EnumSet.of(
                SandboxSessionStatus.PAUSED,
                SandboxSessionStatus.STOPPING,
                SandboxSessionStatus.STOPPED,
                SandboxSessionStatus.FAILED,
                SandboxSessionStatus.DELETING));
        transitions.put(SandboxSessionStatus.PAUSED, EnumSet.of(
                SandboxSessionStatus.STARTING,
                SandboxSessionStatus.RUNNING,
                SandboxSessionStatus.STOPPING,
                SandboxSessionStatus.DELETING));
        transitions.put(SandboxSessionStatus.STOPPING, EnumSet.of(
                SandboxSessionStatus.STOPPED,
                SandboxSessionStatus.FAILED,
                SandboxSessionStatus.DELETING));
        transitions.put(SandboxSessionStatus.STOPPED, EnumSet.of(
                SandboxSessionStatus.STARTING,
                SandboxSessionStatus.RUNNING,
                SandboxSessionStatus.DELETING,
                SandboxSessionStatus.DELETED));
        transitions.put(SandboxSessionStatus.DELETING, EnumSet.of(
                SandboxSessionStatus.DELETED,
                SandboxSessionStatus.FAILED));
        transitions.put(SandboxSessionStatus.FAILED, EnumSet.of(
                SandboxSessionStatus.DELETING,
                SandboxSessionStatus.DELETED));
        transitions.put(SandboxSessionStatus.UNKNOWN, EnumSet.allOf(SandboxSessionStatus.class));
        transitions.put(SandboxSessionStatus.DELETED, EnumSet.noneOf(SandboxSessionStatus.class));
        EnumMap<SandboxSessionStatus, Set<SandboxSessionStatus>> copy =
                new EnumMap<>(SandboxSessionStatus.class);
        transitions.forEach((status, targets) -> copy.put(status, Set.copyOf(targets)));
        return Map.copyOf(copy);
    }
}
