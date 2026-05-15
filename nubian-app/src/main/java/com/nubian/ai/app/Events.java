package com.nubian.ai.app;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Events {

    private Events() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(
            String eventId,
            String runId,
            String type,
            String message,
            Map<String, String> metadata,
            Instant at) {

        public Event {
            if (eventId == null || eventId.isBlank()) eventId = UUID.randomUUID().toString();
            if (at == null) at = Instant.now();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public static Event of(String runId, String type, String message) {
        return new Event(null, runId, type, message, null, null);
    }

    public static Event of(String runId, String type, String message, Map<String, String> meta) {
        return new Event(null, runId, type, message, meta == null ? null : Map.copyOf(meta), null);
    }

    public static MetaBuilder meta() {
        return new MetaBuilder();
    }

    public static final class MetaBuilder {
        private final LinkedHashMap<String, String> data = new LinkedHashMap<>();

        public MetaBuilder put(String key, String value) {
            if (key != null && value != null) data.put(key, value);
            return this;
        }

        public MetaBuilder put(String key, int value) {
            data.put(key, Integer.toString(value));
            return this;
        }

        public MetaBuilder put(String key, long value) {
            data.put(key, Long.toString(value));
            return this;
        }

        public MetaBuilder put(String key, boolean value) {
            data.put(key, Boolean.toString(value));
            return this;
        }

        public Map<String, String> build() {
            return Map.copyOf(data);
        }
    }
}
