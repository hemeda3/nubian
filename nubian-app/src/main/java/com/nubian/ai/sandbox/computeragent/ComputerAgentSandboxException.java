package com.nubian.ai.sandbox.computeragent;

import com.nubian.ai.sandbox.model.SandboxFailure;

import java.util.Objects;

/**
 * Runtime exception carrying a {@link SandboxFailure} detail for the computer-agent provider.
 */
public class ComputerAgentSandboxException extends RuntimeException {

    private final SandboxFailure failure;

    public ComputerAgentSandboxException(SandboxFailure failure) {
        super(Objects.requireNonNull(failure, "failure").message());
        this.failure = failure;
    }

    public SandboxFailure failure() {
        return failure;
    }
}
