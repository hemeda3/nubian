package com.nubian.ai.sandbox.model;

/**
 * Stable error categories for provider-neutral sandbox failures.
 */
public enum SandboxFailureCode {
    UNKNOWN(false),
    PROVIDER_UNAVAILABLE(true),
    AUTHENTICATION_FAILED(false),
    AUTHORIZATION_FAILED(false),
    QUOTA_EXCEEDED(true),
    NOT_FOUND(false),
    CONFLICT(true),
    VALIDATION_ERROR(false),
    TIMEOUT(true),
    UNSUPPORTED_CAPABILITY(false),
    SESSION_ERROR(true),
    FILE_SYSTEM_ERROR(true),
    COMMAND_ERROR(true),
    BROWSER_ERROR(true),
    DISPLAY_ERROR(true),
    PORT_ERROR(true),
    ARTIFACT_ERROR(true),
    INTERNAL_ERROR(true);

    private final boolean retryable;

    SandboxFailureCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
