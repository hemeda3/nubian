package com.nubian.ai.sandbox.store;

import com.nubian.ai.sandbox.model.SandboxSession;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemorySandboxSessionStore implements SandboxSessionStore {
    private final ConcurrentMap<String, SandboxSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<SandboxEvent>> events = new ConcurrentHashMap<>();

    @Override
    public void save(SandboxSession session) {
        sessions.put(key(session.providerId(), session.sessionId()), session);
    }

    @Override
    public Optional<SandboxSession> find(String providerId, String sessionId) {
        return Optional.ofNullable(sessions.get(key(providerId, sessionId)));
    }

    @Override
    public List<SandboxSession> list(String providerId, Map<String, String> labels) {
        Map<String, String> requiredLabels = labels == null ? Map.of() : labels;
        return sessions.values().stream()
                .filter(session -> providerId == null || providerId.isBlank() || providerId.equals(session.providerId()))
                .filter(session -> session.labels().entrySet().containsAll(requiredLabels.entrySet()))
                .sorted(Comparator.comparing(SandboxSession::createdAt))
                .toList();
    }

    @Override
    public void delete(String providerId, String sessionId) {
        sessions.remove(key(providerId, sessionId));
        events.remove(key(providerId, sessionId));
    }

    @Override
    public void appendEvent(SandboxEvent event) {
        events.compute(key(event.providerId(), event.sessionId()), (ignored, existing) -> {
            java.util.ArrayList<SandboxEvent> copy = new java.util.ArrayList<>(
                    existing == null ? List.of() : existing);
            copy.add(event);
            return List.copyOf(copy);
        });
    }

    @Override
    public List<SandboxEvent> listEvents(String providerId, String sessionId) {
        return events.getOrDefault(key(providerId, sessionId), List.of()).stream()
                .sorted(Comparator.comparing(SandboxEvent::occurredAt).thenComparing(SandboxEvent::eventId))
                .toList();
    }

    @Override
    public List<SandboxEvent> listEvents(String providerId) {
        return events.values().stream()
                .flatMap(List::stream)
                .filter(event -> providerId == null || providerId.isBlank() || providerId.equals(event.providerId()))
                .sorted(Comparator.comparing(SandboxEvent::occurredAt).thenComparing(SandboxEvent::eventId))
                .toList();
    }

    private static String key(String providerId, String sessionId) {
        return providerId + "::" + sessionId;
    }
}
