package com.nubian.ai.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component("appOperator")
public final class Operator {

    private static final Logger log = LoggerFactory.getLogger(Operator.class);

    private final Agent agent;
    private final AsyncTaskExecutor taskExecutor;
    private final ConcurrentHashMap<String, RunState> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService disconnectWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-disconnect-watcher");
        t.setDaemon(true);
        return t;
    });

    @Value("${nubian.agent.cancel-on-disconnect-grace-ms:60000}")
    private long disconnectGraceMs = 60000L;

    public Operator(Agent agent, ObjectProvider<AsyncTaskExecutor> taskExecutor) {
        this.agent = agent;
        this.taskExecutor = taskExecutor.getIfAvailable();
    }

    public String startTask(String userTask) {
        String runId = "run-" + UUID.randomUUID();
        RunState state = new RunState(runId);
        runs.put(runId, state);

        Runnable work = () -> {
            Thread current = Thread.currentThread();
            String workerName = "agent-run-" + runId.substring(4, 12);
            try {
                current.setName(workerName);
            } catch (Exception nameErr) {
                log.debug("[run] could not set worker name for {}: {}", runId, nameErr.toString());
            }
            state.thread.set(current);
            log.info("[run] {} worker started thread={} virtual={}", runId, current.getName(), isVirtual(current));
            try {
                Agent.Result result = agent.run(runId, userTask, state::emit, state::isCancellationRequested);
                state.terminal.compareAndSet(null, result.success() ? "completed" : "failed");
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) {
                    if (state.terminal.compareAndSet(null, "cancelled")) {
                        state.emitTerminal(Events.of(runId, "task_cancelled", "Run cancelled by user.",
                                java.util.Map.of("error", ex.getClass().getSimpleName())));
                    }
                } else {
                    log.error("[run] {} threw: {}", runId, ex.toString(), ex);
                    if (state.terminal.compareAndSet(null, "failed")) {
                        state.emitTerminal(Events.of(runId, "task_failed", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                                java.util.Map.of("error", ex.getClass().getSimpleName())));
                    }
                }
            } finally {
                log.info("[run] {} worker exiting terminal={} cancelRequested={} interrupted={}",
                        runId, state.terminal.get(), state.cancelRequested.get(), current.isInterrupted());
                state.complete();
            }
        };
        if (taskExecutor != null) {
            Future<?> future = taskExecutor.submit(work);
            state.future.set(future);
            log.info("[run] {} submitted to Spring AsyncTaskExecutor {}", runId,
                    taskExecutor.getClass().getName());
        } else {
            Thread t = new Thread(work, "agent-run-" + runId.substring(4, 12));
            t.setDaemon(true);
            t.start();
            log.warn("[run] {} started on fallback platform thread; no AsyncTaskExecutor bean available", runId);
        }
        return runId;
    }

    public SseEmitter subscribe(String runId) {
        RunState state = runs.get(runId);
        if (state == null) return null;
        SseEmitter emitter = new SseEmitter(0L);
        for (Events.Event e : state.history) {
            sendOne(emitter, e);
        }
        if (state.terminal.get() != null) {
            try { emitter.complete(); } catch (Exception completeErr) {
                log.debug("[sse] terminal-complete threw: {}", completeErr.toString());
            }
            return emitter;
        }
        cancelPendingDisconnectCancel(state);
        state.emitters.add(emitter);
        emitter.onCompletion(() -> {
            state.emitters.remove(emitter);
            scheduleDisconnectCancel(runId, state);
        });
        emitter.onError(err -> {
            log.debug("[sse] {} errored: {}", runId, err.toString());
            state.emitters.remove(emitter);
            scheduleDisconnectCancel(runId, state);
        });
        emitter.onTimeout(() -> {
            state.emitters.remove(emitter);
            scheduleDisconnectCancel(runId, state);
        });
        return emitter;
    }

    private void scheduleDisconnectCancel(String runId, RunState state) {
        if (disconnectGraceMs <= 0) return;
        if (!state.emitters.isEmpty()) return;
        if (state.terminal.get() != null) return;
        ScheduledFuture<?> task = disconnectWatcher.schedule(() -> {
            if (state.emitters.isEmpty() && state.terminal.get() == null) {
                log.warn("[sse] runId={} no listeners after {}ms grace, auto-cancelling", runId, disconnectGraceMs);
                cancel(runId);
            }
        }, disconnectGraceMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = state.pendingDisconnectCancel.getAndSet(task);
        if (prev != null) prev.cancel(false);
    }

    private void cancelPendingDisconnectCancel(RunState state) {
        ScheduledFuture<?> prev = state.pendingDisconnectCancel.getAndSet(null);
        if (prev != null) prev.cancel(false);
    }

    public CancelResult cancel(String runId) {
        if (runId == null || runId.isBlank()) return new CancelResult(false, false);
        RunState state = runs.get(runId);
        if (state == null) return new CancelResult(false, false);
        state.cancelRequested.set(true);
        Thread t = state.thread.get();
        Future<?> future = state.future.get();
        boolean interrupted = false;
        log.warn("[cancel] runId={} requested terminal={} thread={} alive={} virtual={} futureDone={}",
                runId,
                state.terminal.get(),
                t == null ? "<not-started>" : t.getName(),
                t != null && t.isAlive(),
                t != null && isVirtual(t),
                future != null && future.isDone());
        if (t != null && t.isAlive()) {
            t.interrupt();
            log.warn("[cancel] runId={} interrupt sent to thread={} interruptedNow={}",
                    runId, t.getName(), t.isInterrupted());
            try {
                log.warn("[cancel] runId={} aborting in-flight LLM for thread={}", runId, t.getName());
                LlmClient.abortForThread(t);
            } catch (Exception abortErr) {
                log.warn("[cancel] LLM abortForThread threw: {}", abortErr.toString());
            }
            try {
                log.warn("[cancel] runId={} aborting in-flight sandbox call for thread={}", runId, t.getName());
                Sandbox.abortForThread(t);
            } catch (Exception abortErr) {
                log.warn("[cancel] sandbox abortForThread threw: {}", abortErr.toString());
            }
            try {
                log.warn("[cancel] runId={} aborting in-flight OmniParser call for thread={}", runId, t.getName());
                OmniParserClient.abortForThread(t);
            } catch (Exception abortErr) {
                log.warn("[cancel] omniparser abortForThread threw: {}", abortErr.toString());
            }
            try {
                log.warn("[cancel] runId={} aborting in-flight UGround call for thread={}", runId, t.getName());
                UGroundClient.abortForThread(t);
            } catch (Exception abortErr) {
                log.warn("[cancel] uground abortForThread threw: {}", abortErr.toString());
            }
            interrupted = true;
        }
        if (future != null && !future.isDone()) {
            boolean futureCancelled = future.cancel(true);
            log.warn("[cancel] runId={} future.cancel(true) -> {}", runId, futureCancelled);
        }
        if (state.terminal.compareAndSet(null, "cancelled")) {
            state.emitTerminal(Events.of(runId, "task_cancelled", "Run cancelled by user.",
                    java.util.Map.of("interrupted", String.valueOf(interrupted))));
            state.complete();
        }
        log.warn("[cancel] runId={} cancelled; interrupted={}", runId, interrupted);
        return new CancelResult(true, interrupted);
    }

    public record CancelResult(boolean cancelled, boolean interrupted) {}

    private static final class RunState {
        final String runId;
        final java.util.List<Events.Event> history = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final AtomicReference<ScheduledFuture<?>> pendingDisconnectCancel = new AtomicReference<>();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final AtomicReference<Future<?>> future = new AtomicReference<>();
        final AtomicReference<String> terminal = new AtomicReference<>();
        final AtomicBoolean cancelRequested = new AtomicBoolean(false);

        RunState(String runId) {
            this.runId = runId;
        }

        void emit(Events.Event event) {
            if (terminal.get() != null) return;
            emitNow(event);
            String terminalStatus = terminalStatusFor(event.type());
            if (terminalStatus != null) terminal.compareAndSet(null, terminalStatus);
        }

        void emitTerminal(Events.Event event) {
            emitNow(event);
        }

        boolean isCancellationRequested() {
            return cancelRequested.get() || "cancelled".equals(terminal.get());
        }

        void complete() {
            for (SseEmitter e : emitters) {
                try { e.complete(); } catch (Exception completeErr) {
                    Operator.log.debug("[sse] complete threw for {}: {}", runId, completeErr.toString());
                }
            }
            emitters.clear();
        }

        private void emitNow(Events.Event event) {
            history.add(event);
            for (SseEmitter e : emitters) {
                sendOne(e, event);
            }
        }
    }

    private static String terminalStatusFor(String type) {
        return switch (type == null ? "" : type) {
            case "task_completed" -> "completed";
            case "task_failed" -> "failed";
            case "task_cancelled" -> "cancelled";
            default -> null;
        };
    }

    private static boolean isVirtual(Thread thread) {
        if (thread == null) return false;
        try {
            Object result = Thread.class.getMethod("isVirtual").invoke(thread);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void sendOne(SseEmitter emitter, Events.Event event) {
        try {
            emitter.send(SseEmitter.event()
                    .name("run-event")
                    .id(event.eventId())
                    .data(event));
        } catch (IOException | IllegalStateException ex) {
            Operator.log.debug("[sse] emit failed for {} ({}): {}",
                    event.eventId(), event.type(), ex.toString());
            try { emitter.complete(); } catch (Exception completeErr) {
                Operator.log.debug("[sse] post-error complete threw: {}", completeErr.toString());
            }
        }
    }
}
