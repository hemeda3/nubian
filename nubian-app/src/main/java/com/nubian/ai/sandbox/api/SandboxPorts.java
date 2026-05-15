package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import com.nubian.ai.sandbox.model.SandboxPort;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async port exposure API scoped to a sandbox session.
 */
public interface SandboxPorts extends SandboxCapability {

    @Override
    default SandboxCapabilityType type() {
        return SandboxCapabilityType.PORTS;
    }

    CompletableFuture<SandboxPort> exposePort(String sessionId, int port, String protocol, boolean publicAccess);

    CompletableFuture<List<SandboxPort>> listPorts(String sessionId);

    CompletableFuture<Void> closePort(String sessionId, int port);
}
