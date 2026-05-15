package com.nubian.ai.runtime.db;

import com.nubian.ai.runtime.model.Message;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Small persistence port used by feature modules.
 *
 * <p>This is intentionally storage-neutral. Production modules can replace the
 * default in-memory implementation with SQLite, Postgres, Supabase, or another
 * adapter without leaking provider code into feature modules.</p>
 */
public interface DBConnection {

    CompletableFuture<Message> insertMessage(Message message);

    CompletableFuture<List<Map<String, Object>>> getMessages(String threadId);

    CompletableFuture<List<Map<String, Object>>> getLlmFormattedMessages(String threadId);

    CompletableFuture<List<Map<String, Object>>> queryForList(String tableName, Map<String, Object> conditions);

    default CompletableFuture<List<Map<String, Object>>> queryForList(String tableName, String fieldName, Object value) {
        return queryForList(tableName, Map.of(fieldName, value));
    }

    default CompletableFuture<List<Map<String, Object>>> queryForList(
            String tableName,
            String field1,
            Object value1,
            String field2,
            Object value2) {
        return queryForList(tableName, Map.of(field1, value1, field2, value2));
    }

    default <T> CompletableFuture<T> queryForObject(String sql, Class<T> resultClass, String param) {
        return CompletableFuture.completedFuture(null);
    }

    CompletableFuture<Integer> deleteMessagesByType(String threadId, String messageType);

    CompletableFuture<Integer> update(String tableName, Map<String, Object> values, Map<String, Object> conditions);

    CompletableFuture<Map<String, Object>> insert(String tableName, Map<String, Object> data, boolean upsert);

    CompletableFuture<Integer> delete(String tableName, Map<String, Object> conditions);

    default CompletableFuture<List<Map<String, Object>>> queryForList(String sql, String param) {
        return queryForList(sql, Map.of("param", param));
    }

    default CompletableFuture<List<Map<String, Object>>> queryForList(String sql, String param1, String param2) {
        return queryForList(sql, Map.of("param1", param1, "param2", param2));
    }

    default CompletableFuture<Integer> updateAsync(String sql, String param1, String param2) {
        return CompletableFuture.completedFuture(0);
    }

    default int update(String sql, Object... params) {
        return 0;
    }
}
