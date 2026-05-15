package com.nubian.ai.sandbox.computeragent.adapter;

import com.nubian.ai.sandbox.api.SandboxTerminal;
import com.nubian.ai.sandbox.computeragent.ComputerAgentClient;
import com.nubian.ai.sandbox.computeragent.ComputerAgentException;
import com.nubian.ai.sandbox.computeragent.ComputerAgentResponses.ExecResult;
import com.nubian.ai.sandbox.computeragent.ComputerAgentSandboxException;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxCommandResult;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@link SandboxTerminal} adapter that proxies shell execution to the
 * Ubuntu-desktop guest agent via {@link ComputerAgentClient}.
 *
 * <p>Maps {@code POST /shell/exec} → {@link SandboxCommandResult}.
 * No Spring, no Lombok — pure POJO.
 */
public class ComputerAgentTerminal implements SandboxTerminal {

    static final String PROVIDER_ID = "computer-agent";

    private final String providerId;
    private final ComputerAgentClient client;

    public ComputerAgentTerminal(String providerId, ComputerAgentClient client) {
        if (isBlank(providerId)) {
            throw new IllegalArgumentException("providerId is required");
        }
        this.providerId = providerId;
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "implementation", "computer-agent-guest-api",
                "runtime", "ubuntu-desktop",
                "commandExecution", "/shell/exec");
    }

    @Override
    public CompletableFuture<SandboxCommandResult> execute(String sessionId, SandboxCommand command) {
        SandboxFailure validation = validateCommand(sessionId, command);
        if (validation != null) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(validation));
        }

        return CompletableFuture.supplyAsync(() -> {
            String cwd = isBlank(command.workingDirectory()) ? "/workspace" : command.workingDirectory();
            long timeoutMs = command.timeout() != null ? command.timeout().toMillis() : 120_000L;
            Instant start = Instant.now();
            try {
                ExecResult result = client.exec(command.command(), cwd, timeoutMs);
                Instant end = Instant.now();
                return new SandboxCommandResult(
                        providerId,
                        sessionId,
                        UUID.randomUUID().toString(),
                        result.exitCode(),
                        result.stdout() == null ? "" : result.stdout(),
                        result.stderr() == null ? "" : result.stderr(),
                        start,
                        end,
                        Optional.empty(),
                        Map.of("sandbox.operation", "terminal.execute"));
            } catch (ComputerAgentException ex) {
                Instant end = Instant.now();
                SandboxFailure failure = execFailure(sessionId, ex.getMessage());
                return new SandboxCommandResult(
                        providerId,
                        sessionId,
                        UUID.randomUUID().toString(),
                        -1,
                        "",
                        ex.getMessage() == null ? "" : ex.getMessage(),
                        start,
                        end,
                        Optional.of(failure),
                        Map.of("sandbox.operation", "terminal.execute"));
            }
        });
    }

    @Override
    public CompletableFuture<Void> interrupt(String sessionId, String commandId) {
        if (isBlank(sessionId)) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(new SandboxFailure(
                    providerId, sessionId, SandboxFailureCode.VALIDATION_ERROR,
                    "Session id is required", "terminal.interrupt", false, Map.of())));
        }
        if (isBlank(commandId)) {
            return CompletableFuture.failedFuture(new ComputerAgentSandboxException(new SandboxFailure(
                    providerId, sessionId, SandboxFailureCode.VALIDATION_ERROR,
                    "Command id is required", "terminal.interrupt", false, Map.of())));
        }
        return CompletableFuture.failedFuture(new ComputerAgentSandboxException(new SandboxFailure(
                providerId,
                sessionId,
                SandboxFailureCode.UNSUPPORTED_CAPABILITY,
                "computer-agent guest agent does not expose command interruption yet",
                "terminal.interrupt",
                false,
                Map.of("commandId", commandId))));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SandboxFailure validateCommand(String sessionId, SandboxCommand command) {
        if (isBlank(sessionId)) {
            return new SandboxFailure(
                    providerId, sessionId, SandboxFailureCode.VALIDATION_ERROR,
                    "Session id is required", "terminal.execute", false, Map.of());
        }
        if (command == null || isBlank(command.command())) {
            return new SandboxFailure(
                    providerId, sessionId, SandboxFailureCode.VALIDATION_ERROR,
                    "Command is required", "terminal.execute", false, Map.of());
        }
        return null;
    }

    private SandboxFailure execFailure(String sessionId, String detail) {
        return new SandboxFailure(
                providerId,
                sessionId,
                SandboxFailureCode.COMMAND_ERROR,
                "computer-agent exec failed" + (detail == null || detail.isBlank() ? "" : ": " + detail),
                "terminal.execute",
                true,
                Map.of());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
