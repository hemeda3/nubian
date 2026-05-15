package com.nubian.ai.sandbox.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxSession;
import com.nubian.ai.sandbox.model.SandboxSessionStatus;
import com.nubian.ai.sandbox.store.SandboxEvent;
import com.nubian.ai.sandbox.store.SandboxSessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SqliteSandboxSessionStore implements SandboxSessionStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteSandboxSessionStore.class);
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;
    private static final DateTimeFormatter INSTANT_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final TypeReference<Map<String, String>> STRING_MAP =
            new TypeReference<>() {
            };
    private static final List<String> REQUIRED_SESSION_COLUMNS = List.of(
            "provider_id",
            "session_id",
            "status",
            "created_at",
            "updated_at",
            "labels_json",
            "metadata_json",
            "failure_json");
    private static final List<String> REQUIRED_EVENT_COLUMNS = List.of(
            "event_id",
            "provider_id",
            "session_id",
            "event_type",
            "occurred_at",
            "attributes_json");

    private final String jdbcUrl;
    private final ObjectMapper objectMapper;

    public SqliteSandboxSessionStore(Path databasePath, ObjectMapper objectMapper) {
        Objects.requireNonNull(databasePath, "databasePath is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        createParentDirectory(databasePath);
        initializeSchema();
    }

    @Override
    public void save(SandboxSession session) {
        Objects.requireNonNull(session, "session is required");
        String sql = """
                insert into sandbox_sessions
                    (provider_id, session_id, status, created_at, updated_at, labels_json, metadata_json, failure_json)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(provider_id, session_id) do update set
                    status = excluded.status,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    labels_json = excluded.labels_json,
                    metadata_json = excluded.metadata_json,
                    failure_json = excluded.failure_json
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, session.providerId());
            statement.setString(2, session.sessionId());
            statement.setString(3, session.status().name());
            statement.setString(4, toIso(session.createdAt()));
            statement.setString(5, toIso(session.updatedAt()));
            statement.setString(6, writeJson(session.labels()));
            statement.setString(7, writeJson(session.metadata()));
            statement.setString(8, writeFailure(session.failure()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save sandbox session", e);
        }
    }

    @Override
    public Optional<SandboxSession> find(String providerId, String sessionId) {
        String sql = """
                select provider_id, session_id, status, created_at, updated_at, labels_json, metadata_json, failure_json
                from sandbox_sessions
                where provider_id = ? and session_id = ?
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerId);
            statement.setString(2, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readSession(resultSet));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find sandbox session", e);
        }
    }

    @Override
    public List<SandboxSession> list(String providerId, Map<String, String> labels) {
        String sql = """
                select provider_id, session_id, status, created_at, updated_at, labels_json, metadata_json, failure_json
                from sandbox_sessions
                where (? is null or ? = '' or provider_id = ?)
                order by created_at asc
                """;
        Map<String, String> requiredLabels = labels == null ? Map.of() : labels;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerId);
            statement.setString(2, providerId);
            statement.setString(3, providerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SandboxSession> sessions = new ArrayList<>();
                while (resultSet.next()) {
                    SandboxSession session = readSession(resultSet);
                    if (session.labels().entrySet().containsAll(requiredLabels.entrySet())) {
                        sessions.add(session);
                    }
                }
                return List.copyOf(sessions);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list sandbox sessions", e);
        }
    }

    @Override
    public void delete(String providerId, String sessionId) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "delete from sandbox_events where provider_id = ? and session_id = ?")) {
                    statement.setString(1, providerId);
                    statement.setString(2, sessionId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "delete from sandbox_sessions where provider_id = ? and session_id = ?")) {
                    statement.setString(1, providerId);
                    statement.setString(2, sessionId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete sandbox session", e);
        }
    }

    @Override
    public void appendEvent(SandboxEvent event) {
        Objects.requireNonNull(event, "event is required");
        String sql = """
                insert into sandbox_events
                    (event_id, provider_id, session_id, event_type, occurred_at, attributes_json)
                values (?, ?, ?, ?, ?, ?)
                on conflict(event_id) do nothing
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.providerId());
            statement.setString(3, event.sessionId());
            statement.setString(4, event.eventType());
            statement.setString(5, toIso(event.occurredAt()));
            statement.setString(6, writeJson(event.attributes()));
            int rows = statement.executeUpdate();
            if (rows == 0) {
                assertDuplicateEventMatches(connection, event);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append sandbox event", e);
        }
    }

    @Override
    public List<SandboxEvent> listEvents(String providerId, String sessionId) {
        String sql = """
                select event_id, provider_id, session_id, event_type, occurred_at, attributes_json
                from sandbox_events
                where provider_id = ? and session_id = ?
                order by occurred_at asc, event_id asc
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerId);
            statement.setString(2, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SandboxEvent> events = new ArrayList<>();
                while (resultSet.next()) {
                    events.add(new SandboxEvent(
                            resultSet.getString("event_id"),
                            resultSet.getString("provider_id"),
                            resultSet.getString("session_id"),
                            resultSet.getString("event_type"),
                            parseInstant(resultSet.getString("occurred_at")),
                            readMap(resultSet.getString("attributes_json"))));
                }
                return List.copyOf(events);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list sandbox events", e);
        }
    }

    private void initializeSchema() {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("pragma journal_mode = WAL");
            statement.execute("pragma synchronous = NORMAL");
            statement.executeUpdate("""
                    create table if not exists sandbox_sessions (
                        provider_id text not null,
                        session_id text not null,
                        status text not null,
                        created_at text not null,
                        updated_at text not null,
                        labels_json text not null check (json_valid(labels_json)),
                        metadata_json text not null check (json_valid(metadata_json)),
                        failure_json text check (failure_json is null or json_valid(failure_json)),
                        primary key(provider_id, session_id)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists sandbox_events (
                        event_id text not null primary key,
                        provider_id text not null,
                        session_id text not null,
                        event_type text not null,
                        occurred_at text not null,
                        attributes_json text not null check (json_valid(attributes_json))
                    )
                    """);
            validateSchema(connection);
            statement.executeUpdate("""
                    create index if not exists idx_sandbox_sessions_provider_created
                    on sandbox_sessions(provider_id, created_at)
                    """);
            statement.executeUpdate("""
                    create index if not exists idx_sandbox_events_session
                    on sandbox_events(provider_id, session_id, occurred_at)
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize sandbox SQLite schema", e);
        }
    }

    private SandboxSession readSession(ResultSet resultSet) throws SQLException {
        return new SandboxSession(
                resultSet.getString("provider_id"),
                resultSet.getString("session_id"),
                parseStatus(resultSet.getString("status")),
                parseInstant(resultSet.getString("created_at")),
                parseInstant(resultSet.getString("updated_at")),
                readMap(resultSet.getString("labels_json")),
                readMap(resultSet.getString("metadata_json")),
                readFailure(resultSet.getString("failure_json")));
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        configureConnection(connection);
        return connection;
    }

    private static void configureConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma busy_timeout = " + BUSY_TIMEOUT_MILLIS);
            statement.execute("pragma foreign_keys = ON");
        }
    }

    private static void validateSchema(Connection connection) throws SQLException {
        validateRequiredColumns(connection, "sandbox_sessions", REQUIRED_SESSION_COLUMNS);
        validateRequiredColumns(connection, "sandbox_events", REQUIRED_EVENT_COLUMNS);
        validateNoRows(
                connection,
                "sandbox_sessions",
                """
                        provider_id is null
                        or session_id is null
                        or status is null
                        or created_at is null
                        or updated_at is null
                        or labels_json is null
                        or metadata_json is null
                        """,
                "contains null values in required columns");
        validateNoRows(
                connection,
                "sandbox_events",
                """
                        event_id is null
                        or provider_id is null
                        or session_id is null
                        or event_type is null
                        or occurred_at is null
                        or attributes_json is null
                        """,
                "contains null values in required columns");
        validateNoRows(
                connection,
                "sandbox_sessions",
                """
                        not json_valid(labels_json)
                        or not json_valid(metadata_json)
                        or (failure_json is not null and not json_valid(failure_json))
                        """,
                "contains invalid JSON payloads");
        validateNoRows(
                connection,
                "sandbox_events",
                "not json_valid(attributes_json)",
                "contains invalid JSON payloads");
    }

    private static void validateRequiredColumns(
            Connection connection,
            String tableName,
            List<String> requiredColumns) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("pragma table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                columns.put(resultSet.getString("name"), resultSet.getString("type"));
            }
        }

        List<String> missing = requiredColumns.stream()
                .filter(column -> !columns.containsKey(column))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("SQLite schema table " + tableName
                    + " is missing required columns: " + missing);
        }
    }

    private static void validateNoRows(
            Connection connection,
            String tableName,
            String predicate,
            String failureMessage) throws SQLException {
        String sql = "select 1 from " + tableName + " where " + predicate + " limit 1";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next()) {
                throw new IllegalStateException("SQLite schema table " + tableName + " " + failureMessage);
            }
        }
    }

    private void assertDuplicateEventMatches(Connection connection, SandboxEvent event) throws SQLException {
        String sql = """
                select event_id, provider_id, session_id, event_type, occurred_at, attributes_json
                from sandbox_events
                where event_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.eventId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("SQLite reported duplicate sandbox event id but no row exists: "
                            + event.eventId());
                }
                SandboxEvent existing = new SandboxEvent(
                        resultSet.getString("event_id"),
                        resultSet.getString("provider_id"),
                        resultSet.getString("session_id"),
                        resultSet.getString("event_type"),
                        parseInstant(resultSet.getString("occurred_at")),
                        readMap(resultSet.getString("attributes_json")));
                if (!existing.equals(event)) {
                    throw new IllegalStateException("Sandbox event id collision for event id " + event.eventId());
                }
            }
        }
    }

    private static void rollback(Connection connection, SQLException cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private String writeJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize map", e);
        }
    }

    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize map", e);
        }
    }

    private String writeFailure(Optional<SandboxFailure> failure) {
        if (failure == null || failure.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(failure.get());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize sandbox failure", e);
        }
    }

    private Optional<SandboxFailure> readFailure(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, SandboxFailure.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize sandbox failure", e);
        }
    }

    private static SandboxSessionStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return SandboxSessionStatus.UNKNOWN;
        }
        try {
            return SandboxSessionStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            log.debug("parseStatus fallback: {}", ex.toString());
            return SandboxSessionStatus.UNKNOWN;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        return Instant.parse(value);
    }

    private static String toIso(Instant instant) {
        return INSTANT_FORMATTER.format(instant == null ? Instant.now() : instant);
    }

    private static void createParentDirectory(Path databasePath) {
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create SQLite database directory: " + parent, e);
        }
    }
}
