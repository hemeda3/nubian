package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.model.SandboxFailure;

import java.util.Objects;

public class FirecrackerSandboxException extends RuntimeException {
    private final SandboxFailure failure;

    public FirecrackerSandboxException(SandboxFailure failure) {
        super(message(failure));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public SandboxFailure failure() {
        return failure;
    }

    private static String message(SandboxFailure failure) {
        if (failure == null || failure.message() == null || failure.message().isBlank()) {
            return "Firecracker sandbox operation failed";
        }
        return failure.message();
    }
}
