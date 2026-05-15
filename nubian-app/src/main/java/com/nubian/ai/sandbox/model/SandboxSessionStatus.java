package com.nubian.ai.sandbox.model;

/**
 * Lifecycle state for a sandbox session.
 */
public enum SandboxSessionStatus {
    CREATING(false, false),
    STARTING(false, false),
    RUNNING(true, false),
    PAUSED(false, false),
    STOPPING(false, false),
    STOPPED(false, true),
    DELETING(false, false),
    DELETED(false, true),
    FAILED(false, true),
    UNKNOWN(false, false);

    private final boolean active;
    private final boolean terminal;

    SandboxSessionStatus(boolean active, boolean terminal) {
        this.active = active;
        this.terminal = terminal;
    }

    public boolean active() {
        return active;
    }

    public boolean terminal() {
        return terminal;
    }
}
