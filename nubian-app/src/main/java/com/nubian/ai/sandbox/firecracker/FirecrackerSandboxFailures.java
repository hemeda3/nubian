package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class FirecrackerSandboxFailures {
    private FirecrackerSandboxFailures() {
    }

    static SandboxFailure unsupported(
            String providerId,
            String sessionId,
            String operation,
            String message,
            Map<String, String> metadata) {

        return failure(
                providerId,
                sessionId,
                SandboxFailureCode.UNSUPPORTED_CAPABILITY,
                message,
                operation,
                false,
                metadata);
    }

    static SandboxFailure validation(
            String providerId,
            String sessionId,
            String operation,
            String message) {

        return failure(
                providerId,
                sessionId,
                SandboxFailureCode.VALIDATION_ERROR,
                message,
                operation,
                false,
                Map.of());
    }

    static SandboxFailure notFound(
            String providerId,
            String sessionId,
            String operation,
            String message) {

        return failure(
                providerId,
                sessionId,
                SandboxFailureCode.NOT_FOUND,
                message,
                operation,
                false,
                Map.of());
    }

    static SandboxFailure conflict(
            String providerId,
            String sessionId,
            String operation,
            String message) {

        return failure(
                providerId,
                sessionId,
                SandboxFailureCode.CONFLICT,
                message,
                operation,
                false,
                Map.of());
    }

    static <T> CompletableFuture<T> failedFuture(SandboxFailure failure) {
        return CompletableFuture.failedFuture(new FirecrackerSandboxException(failure));
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static SandboxFailure failure(
            String providerId,
            String sessionId,
            SandboxFailureCode code,
            String message,
            String operation,
            boolean retryable,
            Map<String, String> metadata) {

        return new SandboxFailure(
                providerId,
                sessionId,
                code,
                message,
                operation,
                retryable,
                metadata);
    }
}
