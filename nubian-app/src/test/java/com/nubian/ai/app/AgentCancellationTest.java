package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentCancellationTest {

    @Test
    void cancellationToken_survivesClearedInterruptFlag() {
        Thread current = Thread.currentThread();
        boolean wasInterrupted = current.isInterrupted();
        try {
            current.interrupt();
            Thread.interrupted();

            Agent agent = new Agent(null, null, null, null, null, "<<default>>", 1);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> agent.run("run-cancel-test", "noop", e -> {}, () -> true));
            assertTrue(ex.getCause() instanceof InterruptedException);
        } finally {
            if (wasInterrupted) current.interrupt();
        }
    }
}
