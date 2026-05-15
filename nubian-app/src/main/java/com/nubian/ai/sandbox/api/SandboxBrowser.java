package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxBrowserAction;
import com.nubian.ai.sandbox.model.SandboxBrowserObservation;
import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import java.util.concurrent.CompletableFuture;

/**
 * Async browser automation API scoped to a sandbox session.
 */
public interface SandboxBrowser extends SandboxCapability {

    @Override
    default SandboxCapabilityType type() {
        return SandboxCapabilityType.BROWSER;
    }

    CompletableFuture<SandboxBrowserObservation> performAction(String sessionId, SandboxBrowserAction action);

    CompletableFuture<SandboxBrowserObservation> observe(String sessionId);
}
