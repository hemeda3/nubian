package com.nubian.ai.sandbox.synthesis;

import com.nubian.ai.sandbox.model.SandboxSession;
import com.nubian.ai.sandbox.store.SandboxEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public record SandboxSessionTimeline(
        String providerId,
        String sessionId,
        Optional<SandboxSession> session,
        List<SandboxEvent> events,
        Instant generatedAt) {
    public SandboxSessionTimeline {
        providerId = providerId == null ? "" : providerId;
        sessionId = sessionId == null ? "" : sessionId;
        session = session == null ? Optional.empty() : session;
        events = events == null ? List.of() : events.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(SandboxEvent::occurredAt).thenComparing(SandboxEvent::eventId))
                .toList();
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    public Map<String, Long> eventTypeCounts() {
        return events.stream().collect(Collectors.groupingBy(
                SandboxEvent::eventType,
                java.util.LinkedHashMap::new,
                Collectors.counting()));
    }

    public List<SandboxEvent> events(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return events;
        }
        return events.stream()
                .filter(event -> eventType.equals(event.eventType()))
                .toList();
    }

    public Optional<SandboxEvent> firstEvent() {
        return events.stream().findFirst();
    }

    public Optional<SandboxEvent> latestEvent() {
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(events.get(events.size() - 1));
    }

    public Optional<Instant> startedAt() {
        return firstEvent().map(SandboxEvent::occurredAt)
                .or(() -> session.map(SandboxSession::createdAt));
    }

    public Optional<Instant> lastEventAt() {
        return latestEvent().map(SandboxEvent::occurredAt);
    }

    public Optional<Duration> duration() {
        Optional<Instant> start = startedAt();
        Optional<Instant> end = lastEventAt().or(() -> session.map(SandboxSession::updatedAt));
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(start.get(), end.get()));
    }

    public boolean terminal() {
        return session.map(value -> value.status().terminal()).orElse(false);
    }

    public boolean active() {
        return session.map(value -> value.status().active()).orElse(false);
    }
}
