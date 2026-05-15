package com.nubian.ai.sandbox.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Command request to execute inside a sandbox session.
 */
public record SandboxCommand(
        String command,
        List<String> arguments,
        String workingDirectory,
        Map<String, String> environment,
        Duration timeout,
        boolean interactive,
        Map<String, String> metadata
) {
    public SandboxCommand {
        command = Objects.requireNonNull(command, "command");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        workingDirectory = Objects.requireNonNullElse(workingDirectory, "");
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
