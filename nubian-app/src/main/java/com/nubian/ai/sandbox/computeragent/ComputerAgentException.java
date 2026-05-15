package com.nubian.ai.sandbox.computeragent;

import com.nubian.ai.sandbox.model.SandboxFailure;

/**
 * Thrown when the Ubuntu-desktop guest agent returns a non-2xx HTTP status,
 * or when a transport-level error (IO / interrupt) prevents the call.
 *
 * <p>Also used by adapter classes to wrap a {@link SandboxFailure} into an
 * exception that can be passed to {@link java.util.concurrent.CompletableFuture#failedFuture}.
 */
public class ComputerAgentException extends RuntimeException {

    private final int statusCode;
    private final String endpointPath;
    private final SandboxFailure sandboxFailure;

    public ComputerAgentException(int statusCode, String endpointPath, String message, Throwable cause) {
        super(buildMessage(statusCode, endpointPath, message), cause);
        this.statusCode = statusCode;
        this.endpointPath = endpointPath;
        this.sandboxFailure = null;
    }

    /**
     * Convenience constructor used by adapter classes that have already built a
     * {@link SandboxFailure} and need to wrap it as an exception.
     */
    public ComputerAgentException(SandboxFailure failure) {
        super(failure == null ? "ComputerAgentException" : failure.message());
        this.statusCode = -1;
        this.endpointPath = failure == null ? "" : failure.operation();
        this.sandboxFailure = failure;
    }

    public int getStatusCode() { return statusCode; }
    public String getEndpointPath() { return endpointPath; }
    public SandboxFailure getSandboxFailure() { return sandboxFailure; }

    private static String buildMessage(int statusCode, String endpointPath, String detail) {
        return "ComputerAgentException[status=" + statusCode + ", path=" + endpointPath + "]: " + detail;
    }
}
