package com.nubian.ai.runtime.mcp.tasks;

/**
 * Thrown when an operation is attempted on a task that has exceeded its TTL and is no
 * longer available on the server.
 *
 * <p>Per the MCP spec (2025-11-25, Tasks section): servers MUST honour the {@code ttl}
 * field and MAY delete task state once the TTL has elapsed. Clients that attempt to
 * access such a task will receive an error response; {@link McpTasksClient} translates
 * that into this exception.
 */
public class TaskExpiredException extends RuntimeException {

    private final String taskId;

    /**
     * Constructs a new {@code TaskExpiredException} for the given task identifier.
     *
     * @param taskId the identifier of the expired task
     */
    public TaskExpiredException(String taskId) {
        super("Task has expired and is no longer available: " + taskId);
        this.taskId = taskId;
    }

    /**
     * Constructs a new {@code TaskExpiredException} with a custom message and cause.
     *
     * @param taskId  the identifier of the expired task
     * @param message detail message
     * @param cause   the underlying cause
     */
    public TaskExpiredException(String taskId, String message, Throwable cause) {
        super(message, cause);
        this.taskId = taskId;
    }

    /** Returns the identifier of the task that expired. */
    public String getTaskId() {
        return taskId;
    }
}
