package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.model.SandboxCommand;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record FirecrackerVmOperation(
        String operationId,
        Type type,
        String sessionId,
        Map<String, String> parameters,
        Instant requestedAt) {

    public FirecrackerVmOperation {
        operationId = Objects.requireNonNullElseGet(operationId, () -> "fcop-" + UUID.randomUUID());
        type = Objects.requireNonNull(type, "type");
        sessionId = Objects.requireNonNullElse(sessionId, "");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
    }

    public static FirecrackerVmOperation lifecycle(Type type, String sessionId) {
        return new FirecrackerVmOperation(null, type, sessionId, Map.of(), Instant.now());
    }

    public static FirecrackerVmOperation executeProcess(String sessionId, SandboxCommand command) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("command", command.command());
        parameters.put("arguments", String.join(" ", command.arguments()));
        parameters.put("workingDirectory", command.workingDirectory());
        parameters.put("environmentKeys", String.join(",", command.environment().keySet()));
        parameters.put("interactive", Boolean.toString(command.interactive()));
        if (command.timeout() != null) {
            parameters.put("timeoutMillis", Long.toString(command.timeout().toMillis()));
        }
        return new FirecrackerVmOperation(null, Type.EXECUTE_PROCESS, sessionId, parameters, Instant.now());
    }

    public Map<String, String> toMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("firecracker.operationId", operationId);
        metadata.put("firecracker.operationType", type.name());
        metadata.put("firecracker.sessionId", sessionId);
        metadata.put("firecracker.requestedAt", requestedAt.toString());
        parameters.forEach((key, value) -> metadata.put("firecracker." + key, value));
        return Map.copyOf(metadata);
    }

    public enum Type {
        CREATE_MICROVM,
        START_MICROVM,
        STOP_MICROVM,
        DELETE_MICROVM,
        EXECUTE_PROCESS,
        INTERRUPT_PROCESS,
        READ_FILE,
        WRITE_FILE,
        LIST_FILES,
        CREATE_DIRECTORY,
        DELETE_PATH,
        EXPOSE_PORT,
        LIST_PORTS,
        CLOSE_PORT
    }
}
