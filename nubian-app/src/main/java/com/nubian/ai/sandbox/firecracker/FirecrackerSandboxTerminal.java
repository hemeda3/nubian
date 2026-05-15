package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.api.SandboxTerminal;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxCommandResult;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxSession;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class FirecrackerSandboxTerminal implements SandboxTerminal {
    private final String providerId;
    private final FirecrackerSandboxSessionService sessions;

    public FirecrackerSandboxTerminal(FirecrackerSandboxSessionService sessions) {
        this(FirecrackerSandboxProvider.PROVIDER_ID, sessions);
    }

    public FirecrackerSandboxTerminal(String providerId, FirecrackerSandboxSessionService sessions) {
        if (FirecrackerSandboxFailures.isBlank(providerId)) {
            throw new IllegalArgumentException("providerId is required");
        }
        this.providerId = providerId;
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "implementation", "flyvm-guest-api",
                "runtime", "firecracker",
                "commandExecution", "/v1/computers/{vmId}/exec");
    }

    @Override
    public CompletableFuture<SandboxCommandResult> execute(String sessionId, SandboxCommand command) {
        SandboxFailure validation = validateCommand(sessionId, command, "terminal.execute");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }

        return CompletableFuture.supplyAsync(() -> {
            SandboxSession session = sessions.requireRunningSession(sessionId, "terminal.execute")
                    .orElseThrow();
            return sessions.flyVmClient().execute(
                    sessionId,
                    session.metadata().get("flyvm.agentBaseUrl"),
                    command);
        });
    }

    @Override
    public CompletableFuture<Void> interrupt(String sessionId, String commandId) {
        SandboxFailure validation = sessions.validateKnownSession(sessionId, "terminal.interrupt");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }
        if (FirecrackerSandboxFailures.isBlank(commandId)) {
            return FirecrackerSandboxFailures.failedFuture(FirecrackerSandboxFailures.validation(
                    providerId,
                    sessionId,
                    "terminal.interrupt",
                    "Command id is required"));
        }

        return FirecrackerSandboxFailures.failedFuture(FirecrackerSandboxFailures.unsupported(
                providerId,
                sessionId,
                "terminal.interrupt",
                "FlyVM guest agent does not expose command interruption yet",
                Map.of("commandId", commandId)));
    }

    private SandboxFailure validateCommand(String sessionId, SandboxCommand command, String operation) {
        SandboxFailure validation = sessions.validateKnownSession(sessionId, operation);
        if (validation != null) {
            return validation;
        }
        if (command == null || FirecrackerSandboxFailures.isBlank(command.command())) {
            return FirecrackerSandboxFailures.validation(
                    providerId,
                    sessionId,
                    operation,
                    "Command is required");
        }
        return null;
    }
}
