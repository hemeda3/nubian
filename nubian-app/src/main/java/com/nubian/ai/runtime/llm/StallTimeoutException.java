package com.nubian.ai.runtime.llm;

/**
 * Thrown by {@link StreamingWatchdog} when no chunk tick is received within
 * the configured stall timeout duration.
 */
public class StallTimeoutException extends RuntimeException {

    public StallTimeoutException(String message) {
        super(message);
    }

    public StallTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
