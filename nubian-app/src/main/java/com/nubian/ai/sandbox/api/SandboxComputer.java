package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import com.nubian.ai.sandbox.model.SandboxComputerEnvironment;
import java.util.concurrent.CompletableFuture;

/**
 * Manifest capability for a provider-backed computer sandbox.
 *
 * Action capabilities such as terminal, browser, display, files, ports, and
 * artifacts do the work. This capability tells the agent what tools and
 * folders are actually present in a concrete session.
 */
public interface SandboxComputer extends SandboxCapability {

    @Override
    default SandboxCapabilityType type() {
        return SandboxCapabilityType.COMPUTER;
    }

    CompletableFuture<SandboxComputerEnvironment> inspect(String sessionId);
}
