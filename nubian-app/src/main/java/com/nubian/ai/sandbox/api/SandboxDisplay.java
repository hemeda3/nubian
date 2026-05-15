package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import com.nubian.ai.sandbox.model.SandboxDisplayFrame;
import java.util.concurrent.CompletableFuture;

/**
 * Async display API scoped to a sandbox session.
 */
public interface SandboxDisplay extends SandboxCapability {

    @Override
    default SandboxCapabilityType type() {
        return SandboxCapabilityType.DISPLAY;
    }

    CompletableFuture<SandboxDisplayFrame> captureFrame(String sessionId);

    CompletableFuture<Void> resizeDisplay(String sessionId, int width, int height);
}
