package com.nubian.ai.runtime.mcp.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.ErrorObject;
import com.nubian.ai.runtime.mcp.protocol.JsonRpcMessage;
import com.nubian.ai.runtime.mcp.protocol.Notification;
import com.nubian.ai.runtime.mcp.protocol.Request;
import com.nubian.ai.runtime.mcp.protocol.RequestId;
import com.nubian.ai.runtime.mcp.protocol.Response;
import com.nubian.ai.runtime.mcp.transport.McpTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * High-level client for MCP Tasks (experimental, 2025-11-25 spec).
 *
 * <p>Provides typed wrappers over the raw JSON-RPC methods defined in the Tasks section
 * of the MCP specification:
 * <ul>
 *   <li>{@code tasks/get} — retrieve current task state</li>
 *   <li>{@code tasks/result} — retrieve the final result of a terminal task</li>
 *   <li>{@code tasks/cancel} — request cancellation of a running task</li>
 *   <li>{@code tasks/list} — list tasks with optional cursor-based pagination</li>
 *   <li>{@code notifications/tasks/status} — server-push status change notifications</li>
 * </ul>
 *
 * <p><strong>Access control note:</strong> The MCP spec mandates that when an
 * authentication layer is present, each task MUST be bound to the auth context that
 * created it. Servers MUST reject {@code tasks/get}, {@code tasks/result},
 * {@code tasks/cancel}, and {@code tasks/list} calls made by a principal other than the
 * one that owns the task. This class is transport-level only; enforcement of the
 * ownership invariant is the responsibility of the server-side orchestrator that
 * creates and manages task records — it must attach the auth context at creation time
 * and verify it on every subsequent request.
 *
 * <p>This class is intentionally free of Spring annotations so that it can be
 * instantiated directly in any environment (tests, non-Spring runtimes, etc.).
 */
public class McpTasksClient {

    private static final String METHOD_TASKS_GET    = "tasks/get";
    private static final String METHOD_TASKS_RESULT = "tasks/result";
    private static final String METHOD_TASKS_CANCEL = "tasks/cancel";
    private static final String METHOD_TASKS_LIST   = "tasks/list";
    private static final String NOTIF_TASKS_STATUS  = "notifications/tasks/status";

    /** Terminal-state message fragment used by some servers to signal cancel-on-terminal. */
    private static final String TERMINAL_STATUS_MSG = "already in terminal status";

    /** Default poll interval when the server does not advertise one (1 second). */
    private static final long DEFAULT_POLL_INTERVAL_MS = 1_000L;

    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates a client with a default {@link ObjectMapper} and a single-threaded
     * internal scheduler used for {@link #awaitTerminal} polling.
     */
    public McpTasksClient() {
        this(new ObjectMapper(), new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "mcp-tasks-poll");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * Creates a client with the supplied mapper and scheduler.
     *
     * @param mapper    Jackson mapper used for serialisation/deserialisation
     * @param scheduler executor used for polling in {@link #awaitTerminal}
     */
    public McpTasksClient(ObjectMapper mapper, ScheduledExecutorService scheduler) {
        this.mapper    = mapper;
        this.scheduler = scheduler;
    }

    // -------------------------------------------------------------------------
    // tasks/get
    // -------------------------------------------------------------------------

    /**
     * Retrieves the current state of a task.
     *
     * @param transport the MCP transport to use
     * @param taskId    the task identifier
     * @return a future that resolves to the {@link Task}, or fails with a
     *         {@link TaskExpiredException} if the server indicates the task has expired
     */
    public CompletableFuture<Task> getTask(McpTransport transport, String taskId) {
        Request req = buildRequest(METHOD_TASKS_GET,
                mapper.valueToTree(new TaskGetParams(taskId)));
        return transport.send(req).thenApply(resp -> {
            JsonNode result = successResult(resp, taskId);
            return mapper.convertValue(result, Task.class);
        });
    }

    // -------------------------------------------------------------------------
    // tasks/result
    // -------------------------------------------------------------------------

    /**
     * Retrieves the final result of a task.
     *
     * <p>Per the spec the server SHOULD block until the task reaches a terminal state
     * before responding. The result shape is server-defined; callers are responsible for
     * deserialising the returned {@link JsonNode} into the appropriate type.
     *
     * @param transport the MCP transport to use
     * @param taskId    the task identifier
     * @return a future that resolves to the raw result node
     */
    public CompletableFuture<JsonNode> getTaskResult(McpTransport transport, String taskId) {
        Request req = buildRequest(METHOD_TASKS_RESULT,
                mapper.valueToTree(new TaskResultParams(taskId)));
        return transport.send(req).thenApply(resp -> successResult(resp, taskId));
    }

    // -------------------------------------------------------------------------
    // tasks/cancel
    // -------------------------------------------------------------------------

    /**
     * Requests cancellation of a running task.
     *
     * <p>If the server returns {@code INVALID_PARAMS} (-32602) with a message
     * containing {@value #TERMINAL_STATUS_MSG} this method throws
     * {@link TaskTerminalException} instead of a generic error.
     *
     * @param transport the MCP transport to use
     * @param taskId    the task identifier
     * @return a future that resolves to the updated {@link Task} (status CANCELLED)
     * @throws TaskTerminalException (wrapped in the future) when the task is already
     *                               in a terminal state
     */
    public CompletableFuture<Task> cancelTask(McpTransport transport, String taskId) {
        Request req = buildRequest(METHOD_TASKS_CANCEL,
                mapper.valueToTree(new TaskCancelParams(taskId)));
        return transport.send(req).thenApply(resp -> {
            if (resp instanceof Response.ErrorResponse err) {
                ErrorObject error = err.error();
                if (error.code() == ErrorObject.INVALID_PARAMS
                        && error.message() != null
                        && error.message().toLowerCase().contains(TERMINAL_STATUS_MSG)) {
                    throw new TaskTerminalException(taskId,
                            "Cannot cancel task in terminal state: " + error.message(), null);
                }
                throw new RuntimeException("tasks/cancel failed [" + error.code() + "]: "
                        + error.message());
            }
            Response.SuccessResponse ok = (Response.SuccessResponse) resp;
            return mapper.convertValue(ok.result(), Task.class);
        });
    }

    // -------------------------------------------------------------------------
    // tasks/list
    // -------------------------------------------------------------------------

    /**
     * Lists tasks, returning a single page.
     *
     * @param transport the MCP transport to use
     * @param cursor    pagination cursor from a previous {@link TaskListResult#nextCursor()};
     *                  {@code null} to start from the beginning
     * @return a future that resolves to a {@link TaskListResult}
     */
    public CompletableFuture<TaskListResult> listTasks(McpTransport transport, String cursor) {
        Request req = buildRequest(METHOD_TASKS_LIST,
                mapper.valueToTree(new TaskListParams(cursor)));
        return transport.send(req).thenApply(resp -> {
            JsonNode result = successResult(resp, null);
            return mapper.convertValue(result, TaskListResult.class);
        });
    }

    /**
     * Fetches all tasks by following pagination cursors until exhausted.
     *
     * @param transport the MCP transport to use
     * @return a future that resolves to the complete list of all tasks
     */
    public CompletableFuture<List<Task>> listAllTasks(McpTransport transport) {
        return listAllTasksRecursive(transport, null, new ArrayList<>());
    }

    private CompletableFuture<List<Task>> listAllTasksRecursive(
            McpTransport transport, String cursor, List<Task> accumulated) {
        return listTasks(transport, cursor).thenCompose(page -> {
            if (page.tasks() != null) {
                accumulated.addAll(page.tasks());
            }
            if (page.nextCursor() == null || page.nextCursor().isBlank()) {
                return CompletableFuture.completedFuture(accumulated);
            }
            return listAllTasksRecursive(transport, page.nextCursor(), accumulated);
        });
    }

    // -------------------------------------------------------------------------
    // notifications/tasks/status
    // -------------------------------------------------------------------------

    /**
     * Registers a handler for {@code notifications/tasks/status} notifications for the
     * given task.
     *
     * <p>The handler is automatically deregistered when the notification carries a
     * terminal status ({@link TaskStatus#isTerminal}).
     *
     * @param transport the MCP transport to subscribe on
     * @param taskId    only notifications for this task ID will be dispatched
     * @param handler   consumer called on each matching notification
     * @return an {@link AutoCloseable} that, when closed, deregisters the handler
     *         before the task reaches a terminal state
     */
    public AutoCloseable onStatusNotification(McpTransport transport,
                                              String taskId,
                                              Consumer<TaskStatusNotificationParams> handler) {
        AtomicBoolean active = new AtomicBoolean(true);

        Consumer<JsonRpcMessage> incomingHandler = msg -> {
            if (!active.get()) {
                return;
            }
            if (!(msg instanceof Notification notif)) {
                return;
            }
            if (!NOTIF_TASKS_STATUS.equals(notif.method())) {
                return;
            }
            if (notif.params() == null) {
                return;
            }
            TaskStatusNotificationParams params;
            try {
                params = mapper.treeToValue(notif.params(),
                        TaskStatusNotificationParams.class);
            } catch (Exception e) {
                return; // malformed notification — skip
            }
            if (!taskId.equals(params.taskId())) {
                return;
            }
            handler.accept(params);
            if (params.status() != null && TaskStatus.isTerminal(params.status())) {
                active.set(false);
            }
        };

        transport.onIncoming(incomingHandler);

        return () -> active.set(false);
    }

    // -------------------------------------------------------------------------
    // awaitTerminal
    // -------------------------------------------------------------------------

    /**
     * High-level helper that polls {@code tasks/get} until the task reaches a terminal
     * state, then fetches and returns the final result via {@code tasks/result}.
     *
     * <p>The poll interval is determined as follows (first applicable wins):
     * <ol>
     *   <li>{@code pollOverride} — when non-null this duration is used unconditionally.</li>
     *   <li>The {@code pollInterval} field on the {@link Task} returned by
     *       {@code tasks/get} — the server-advertised millisecond interval.</li>
     *   <li>{@value #DEFAULT_POLL_INTERVAL_MS} ms default.</li>
     * </ol>
     *
     * @param transport    the MCP transport to use
     * @param taskId       the task identifier to monitor
     * @param pollOverride override for the poll interval; {@code null} to use the
     *                     server-advertised value or the default
     * @return a future that resolves to the raw result {@link JsonNode} once the task
     *         is terminal
     */
    public CompletableFuture<JsonNode> awaitTerminal(McpTransport transport,
                                                     String taskId,
                                                     Duration pollOverride) {
        CompletableFuture<JsonNode> promise = new CompletableFuture<>();
        scheduleGetPoll(transport, taskId, pollOverride, promise);
        return promise;
    }

    private void scheduleGetPoll(McpTransport transport,
                                 String taskId,
                                 Duration pollOverride,
                                 CompletableFuture<JsonNode> promise) {
        getTask(transport, taskId).whenComplete((task, ex) -> {
            if (promise.isDone()) {
                return;
            }
            if (ex != null) {
                promise.completeExceptionally(ex);
                return;
            }
            if (TaskStatus.isTerminal(task.status())) {
                getTaskResult(transport, taskId).whenComplete((result, resultEx) -> {
                    if (resultEx != null) {
                        promise.completeExceptionally(resultEx);
                    } else {
                        promise.complete(result);
                    }
                });
                return;
            }
            long delayMs = resolveDelay(task, pollOverride);
            scheduler.schedule(
                    () -> scheduleGetPoll(transport, taskId, pollOverride, promise),
                    delayMs,
                    TimeUnit.MILLISECONDS);
        });
    }

    private long resolveDelay(Task task, Duration pollOverride) {
        if (pollOverride != null) {
            return pollOverride.toMillis();
        }
        if (task.pollInterval() != null && task.pollInterval() > 0) {
            return task.pollInterval();
        }
        return DEFAULT_POLL_INTERVAL_MS;
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private Request buildRequest(String method, JsonNode params) {
        RequestId id = RequestId.of(UUID.randomUUID().toString());
        return new Request(id, method, params);
    }

    /**
     * Extracts the {@code result} node from a success response, or throws on error.
     *
     * @param resp   the response to inspect
     * @param taskId used only in exception messages; may be {@code null}
     */
    private JsonNode successResult(Response resp, String taskId) {
        if (resp instanceof Response.ErrorResponse err) {
            ErrorObject error = err.error();
            String ctx = taskId != null ? " (task: " + taskId + ")" : "";
            throw new RuntimeException("MCP error" + ctx + " [" + error.code() + "]: "
                    + error.message());
        }
        return ((Response.SuccessResponse) resp).result();
    }
}
