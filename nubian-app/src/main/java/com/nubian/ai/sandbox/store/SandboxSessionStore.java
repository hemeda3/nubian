package com.nubian.ai.sandbox.store;

import com.nubian.ai.sandbox.model.SandboxSession;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SandboxSessionStore {
    void save(SandboxSession session);

    Optional<SandboxSession> find(String providerId, String sessionId);

    List<SandboxSession> list(String providerId, Map<String, String> labels);

    void delete(String providerId, String sessionId);

    void appendEvent(SandboxEvent event);

    List<SandboxEvent> listEvents(String providerId, String sessionId);

    default List<SandboxEvent> listEvents(String providerId) {
        return list(providerId, Map.of()).stream()
                .flatMap(session -> listEvents(providerId, session.sessionId()).stream())
                .sorted(Comparator.comparing(SandboxEvent::occurredAt).thenComparing(SandboxEvent::eventId))
                .toList();
    }
}
