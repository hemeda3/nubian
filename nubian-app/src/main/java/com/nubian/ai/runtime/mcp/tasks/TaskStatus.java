package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle status values for an MCP task (2025-11-25 spec).
 *
 * <p>Terminal states are {@link #COMPLETED}, {@link #FAILED}, and {@link #CANCELLED}.
 * Once a task reaches a terminal state the server MUST NOT transition it to any other
 * state.
 *
 * <p>JSON serialisation uses snake_case per the spec
 * (e.g. {@code "input_required"} not {@code "INPUT_REQUIRED"}).
 */
public enum TaskStatus {

    /** Task is actively being processed. */
    WORKING("working"),

    /** Task is paused and requires additional input from the client. */
    INPUT_REQUIRED("input_required"),

    /** Task finished successfully. Terminal. */
    COMPLETED("completed"),

    /** Task finished with an error. Terminal. */
    FAILED("failed"),

    /** Task was cancelled by the client or server. Terminal. */
    CANCELLED("cancelled");

    private final String jsonValue;

    TaskStatus(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /** Returns the wire-format JSON value (snake_case). */
    @JsonValue
    public String getJsonValue() {
        return jsonValue;
    }

    /**
     * Deserialises from the wire-format snake_case string.
     *
     * @throws IllegalArgumentException if the value is unknown
     */
    @JsonCreator
    public static TaskStatus fromJsonValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TaskStatus value must not be null");
        }
        for (TaskStatus s : values()) {
            if (s.jsonValue.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown TaskStatus: " + value);
    }

    /**
     * Returns {@code true} when the status represents a terminal state, i.e. the task
     * will never change status again.
     *
     * <p>Terminal statuses: {@link #COMPLETED}, {@link #FAILED}, {@link #CANCELLED}.
     */
    public static boolean isTerminal(TaskStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        return status == COMPLETED || status == FAILED || status == CANCELLED;
    }
}
