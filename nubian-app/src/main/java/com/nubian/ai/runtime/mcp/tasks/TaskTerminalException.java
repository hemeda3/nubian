package com.nubian.ai.runtime.mcp.tasks;

/**
 * Thrown when a client attempts to cancel a task that has already reached a terminal
 * state ({@link TaskStatus#COMPLETED}, {@link TaskStatus#FAILED}, or
 * {@link TaskStatus#CANCELLED}).
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): if the server returns an
 * {@code INVALID_PARAMS} (-32602) error whose message indicates the task is already in a
 * terminal status, {@link McpTasksClient#cancelTask} translates that into this
 * exception rather than a generic error.
 */
public class TaskTerminalException extends RuntimeException {

    private final String taskId;

    /**
     * Constructs a new {@code TaskTerminalException} for the given task identifier.
     *
     * @param taskId the identifier of the task that is already terminal
     */
    public TaskTerminalException(String taskId) {
        super("Task is already in a terminal state and cannot be cancelled: " + taskId);
        this.taskId = taskId;
    }

    /**
     * Constructs a new {@code TaskTerminalException} with a custom message and cause.
     *
     * @param taskId  the identifier of the task
     * @param message detail message
     * @param cause   the underlying cause
     */
    public TaskTerminalException(String taskId, String message, Throwable cause) {
        super(message, cause);
        this.taskId = taskId;
    }

    /** Returns the identifier of the task that was already terminal. */
    public String getTaskId() {
        return taskId;
    }
}
