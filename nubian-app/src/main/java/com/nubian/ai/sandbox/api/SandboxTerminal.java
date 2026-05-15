package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxCommandResult;
import java.util.concurrent.CompletableFuture;

/**
 * Async terminal API scoped to a sandbox session.
 */
public interface SandboxTerminal extends SandboxCapability {

    @Override
    default SandboxCapabilityType type() {
        return SandboxCapabilityType.TERMINAL;
    }

    CompletableFuture<SandboxCommandResult> execute(String sessionId, SandboxCommand command);

    CompletableFuture<Void> interrupt(String sessionId, String commandId);
}
