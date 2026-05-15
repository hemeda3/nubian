package com.nubian.ai.runtime.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Safety watchdog for streaming LLM responses.
 *
 * <p>LlmClient currently uses single-shot HTTP/1.1 (non-streaming) responses to
 * avoid the bad_record_mac TLS issue. This watchdog is wired in as a SAFETY NET
 * for when streaming is added later — it does NOT intercept the existing
 * non-streaming chat path.
 *
 * <p>Usage pattern:
 * <pre>{@code
 * StreamingWatchdog watchdog = new StreamingWatchdog(Duration.ofSeconds(15));
 * try (StreamingWatchdog.WatchdogSession session = watchdog.start(onStallCallback)) {
 *     // for each incoming chunk:
 *     session.tick();
 * }
 * }</pre>
 *
 * <p>Thread safety: {@link WatchdogSession#tick()} is safe to call from multiple
 * concurrent threads.
 */
public class StreamingWatchdog {

    private static final Logger log = LoggerFactory.getLogger(StreamingWatchdog.class);
    private final Duration stallTimeout;

    /**
     * Creates a watchdog with the specified default stall timeout.
     *
     * @param stallTimeout how long to wait without a tick before firing the stall callback
     */
    public StreamingWatchdog(Duration stallTimeout) {
        if (stallTimeout == null || stallTimeout.isNegative() || stallTimeout.isZero()) {
            throw new IllegalArgumentException("stallTimeout must be positive");
        }
        this.stallTimeout = stallTimeout;
    }

    /**
     * Creates a watchdog with the default 15-second stall timeout.
     */
    public StreamingWatchdog() {
        this(Duration.ofSeconds(15));
    }

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------

    /**
     * Starts a new watchdog session. The {@code onStall} callback is invoked at most
     * once if no {@link WatchdogSession#tick()} is received within the stall timeout.
     *
     * @param onStall runnable invoked on the scheduler thread when a stall is detected
     * @return an AutoCloseable session; call {@link WatchdogSession#close()} (or use
     *         try-with-resources) to cancel the timer when streaming completes normally
     */
    public WatchdogSession start(Runnable onStall) {
        return start(stallTimeout, onStall);
    }

    /**
     * Starts a new watchdog session with an explicit timeout (overrides the default).
     *
     * @param timeout  per-session stall timeout
     * @param onStall  runnable invoked on stall
     * @return the session
     */
    public WatchdogSession start(Duration timeout, Runnable onStall) {
        return new WatchdogSession(timeout, onStall);
    }

    // -------------------------------------------------------------------------
    // WatchdogSession
    // -------------------------------------------------------------------------

    /**
     * An active watchdog session. Call {@link #tick()} on every received chunk to
     * reset the stall timer. Call {@link #close()} (or use try-with-resources) to
     * cancel the timer when streaming completes.
     */
    public static final class WatchdogSession implements AutoCloseable {

        private final Duration timeout;
        private final Runnable onStall;
        private final ScheduledExecutorService scheduler;
        private final Lock lock = new ReentrantLock();
        private final AtomicBoolean stalled = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private ScheduledFuture<?> pending;

        WatchdogSession(Duration timeout, Runnable onStall) {
            this.timeout = timeout;
            this.onStall = onStall != null ? onStall : () -> {};
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "streaming-watchdog");
                t.setDaemon(true);
                return t;
            });
            schedule();
        }

        /**
         * Resets the stall timer. Call this every time a chunk arrives from the
         * streaming response. Thread-safe; may be called from any thread.
         */
        public void tick() {
            if (closed.get()) return;
            lock.lock();
            try {
                if (pending != null) {
                    pending.cancel(false);
                }
                if (!closed.get()) {
                    schedule();
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns {@code true} if the stall callback has already fired.
         */
        public boolean isStalled() {
            return stalled.get();
        }

        /**
         * Cancels the timer. No stall callback will fire after this returns.
         * Safe to call multiple times.
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                lock.lock();
                try {
                    if (pending != null) {
                        pending.cancel(false);
                        pending = null;
                    }
                } finally {
                    lock.unlock();
                }
                scheduler.shutdownNow();
            }
        }

        // ------------------------------------------------------------------

        private void schedule() {
            pending = scheduler.schedule(this::onTimeout,
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void onTimeout() {
            if (closed.get()) return;
            stalled.set(true);
            try {
                onStall.run();
            } catch (Exception ex) {
                log.warn("onTimeout stall callback failed: {}", ex.toString());
                // Swallow exceptions from the callback to protect the scheduler thread.
            }
        }
    }
}
