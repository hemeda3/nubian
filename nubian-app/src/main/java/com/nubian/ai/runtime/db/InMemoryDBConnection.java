package com.nubian.ai.runtime.db;

import com.nubian.ai.runtime.model.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

/**
 * Default local persistence adapter for development, tests, and starter usage.
 */
@Service
public class InMemoryDBConnection implements DBConnection {
    private final Map<String, List<Map<String, Object>>> tables = new HashMap<>();
    private long memoryOffsetSequence = 1;

    @Override
    public synchronized CompletableFuture<Message> insertMessage(Message message) {
        Message safeMessage = message == null ? new Message() : message;
        Map<String, Object> row = new HashMap<>();
        row.put("message_id", safeMessage.getMessageId());
        row.put("thread_id", safeMessage.getThreadId());
        row.put("type", safeMessage.getType());
        row.put("content", safeMessage.getContent());
        row.put("is_llm_message", safeMessage.isLlmMessage());
        row.put("metadata", safeMessage.getMetadata());
        row.put("created_at", safeMessage.getCreatedAt());
        row.put("updated_at", safeMessage.getUpdatedAt());
        insert("messages", row, false).join();
        return CompletableFuture.completedFuture(safeMessage);
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> getMessages(String threadId) {
        return queryForList("messages", Map.of("thread_id", threadId))
                .thenApply(rows -> rows.stream()
                        .sorted(Comparator.comparing(row -> String.valueOf(row.getOrDefault("created_at", ""))))
                        .toList());
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> getLlmFormattedMessages(String threadId) {
        return getMessages(threadId).thenApply(rows -> rows.stream()
                .map(row -> {
                    Map<String, Object> formatted = new HashMap<>();
                    formatted.put("role", row.get("type"));
                    formatted.put("content", row.get("content"));
                    return formatted;
                })
                .toList());
    }

    @Override
    public synchronized CompletableFuture<List<Map<String, Object>>> queryForList(
            String tableName,
            Map<String, Object> conditions) {
        Map<String, Object> safeConditions = conditions == null ? Map.of() : conditions;
        List<Map<String, Object>> rows = tables.getOrDefault(tableName, List.of()).stream()
                .filter(row -> matches(row, safeConditions))
                .map(HashMap::new)
                .map(row -> (Map<String, Object>) row)
                .toList();
        return CompletableFuture.completedFuture(rows);
    }

    @Override
    public synchronized CompletableFuture<Integer> deleteMessagesByType(String threadId, String messageType) {
        return delete("messages", Map.of("thread_id", threadId, "type", messageType));
    }

    @Override
    public synchronized CompletableFuture<Integer> update(
            String tableName,
            Map<String, Object> values,
            Map<String, Object> conditions) {
        int updated = 0;
        Map<String, Object> safeValues = values == null ? Map.of() : values;
        Map<String, Object> safeConditions = conditions == null ? Map.of() : conditions;
        for (Map<String, Object> row : tables.getOrDefault(tableName, List.of())) {
            if (matches(row, safeConditions)) {
                row.putAll(safeValues);
                row.put("updated_at", row.getOrDefault("updated_at", Instant.now()));
                updated++;
            }
        }
        return CompletableFuture.completedFuture(updated);
    }

    @Override
    public synchronized CompletableFuture<Map<String, Object>> insert(
            String tableName,
            Map<String, Object> data,
            boolean upsert) {
        Map<String, Object> row = new HashMap<>(data == null ? Map.of() : data);
        row.putIfAbsent("id", UUID.randomUUID().toString());
        if ("agent_memory_events".equals(tableName) && !row.containsKey("wal_offset")) {
            row.put("wal_offset", memoryOffsetSequence++);
        }
        if (upsert) {
            Object id = row.get("id");
            List<Map<String, Object>> rows = tables.computeIfAbsent(tableName, ignored -> new ArrayList<>());
            for (Map<String, Object> existing : rows) {
                if (Objects.equals(existing.get("id"), id)) {
                    existing.putAll(row);
                    return CompletableFuture.completedFuture(new HashMap<>(existing));
                }
            }
        }
        tables.computeIfAbsent(tableName, ignored -> new ArrayList<>()).add(row);
        return CompletableFuture.completedFuture(new HashMap<>(row));
    }

    @Override
    public synchronized CompletableFuture<Integer> delete(String tableName, Map<String, Object> conditions) {
        List<Map<String, Object>> rows = tables.getOrDefault(tableName, new ArrayList<>());
        int before = rows.size();
        Map<String, Object> safeConditions = conditions == null ? Map.of() : conditions;
        rows.removeIf(row -> matches(row, safeConditions));
        return CompletableFuture.completedFuture(before - rows.size());
    }

    private boolean matches(Map<String, Object> row, Map<String, Object> conditions) {
        for (Map.Entry<String, Object> condition : conditions.entrySet()) {
            Object actual = row.get(condition.getKey());
            Object expected = condition.getValue();
            if (!Objects.equals(actual, expected)
                    && (actual == null || expected == null || !actual.toString().equals(expected.toString()))) {
                return false;
            }
        }
        return true;
    }
}
