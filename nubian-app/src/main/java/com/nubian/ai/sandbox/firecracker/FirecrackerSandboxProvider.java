package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.api.SandboxPorts;
import com.nubian.ai.sandbox.api.SandboxProvider;
import com.nubian.ai.sandbox.api.SandboxSessionService;
import com.nubian.ai.sandbox.api.SandboxTerminal;
import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.api.SandboxArtifacts;
import com.nubian.ai.sandbox.api.SandboxComputer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class FirecrackerSandboxProvider implements SandboxProvider {
    public static final String PROVIDER_ID = "firecracker";

    private final FirecrackerSandboxSessionService sessions;
    private final FirecrackerSandboxTerminal terminal;
    private final FirecrackerSandboxFileSystem fileSystem;
    private final FirecrackerSandboxPorts ports;
    private final FirecrackerSandboxDisplay display;
    private final FirecrackerSandboxArtifacts artifacts;
    private final FirecrackerSandboxComputer computer;

    public FirecrackerSandboxProvider() {
        this(new FirecrackerSandboxSessionService(PROVIDER_ID));
    }

    public FirecrackerSandboxProvider(FirecrackerSandboxSessionService sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.terminal = new FirecrackerSandboxTerminal(sessions.providerId(), sessions);
        this.fileSystem = new FirecrackerSandboxFileSystem(sessions.providerId(), sessions);
        this.ports = new FirecrackerSandboxPorts(sessions.providerId(), sessions);
        this.display = new FirecrackerSandboxDisplay(sessions.providerId(), sessions);
        this.artifacts = new FirecrackerSandboxArtifacts(sessions.providerId(), sessions, fileSystem);
        this.computer = new FirecrackerSandboxComputer(sessions.providerId(), sessions, ports);
    }

    @Override
    public String providerId() {
        return sessions.providerId();
    }

    @Override
    public String displayName() {
        return "Firecracker";
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "implementation", "flyvm",
                "runtime", "firecracker",
                "microvmLifecycle", "flyvm-scheduler",
                "commandExecution", "flyvm-guest-api");
    }

    @Override
    public SandboxSessionService sessions() {
        return sessions;
    }

    @Override
    public Optional<SandboxFileSystem> fileSystem() {
        return Optional.of(fileSystem);
    }

    @Override
    public Optional<SandboxTerminal> terminal() {
        return Optional.of(terminal);
    }

    @Override
    public Optional<SandboxPorts> ports() {
        return Optional.of(ports);
    }

    @Override
    public Optional<SandboxDisplay> display() {
        return Optional.of(display);
    }

    @Override
    public Optional<SandboxArtifacts> artifacts() {
        return Optional.of(artifacts);
    }

    @Override
    public Optional<SandboxComputer> computer() {
        return Optional.of(computer);
    }

    public FirecrackerSandboxSessionService firecrackerSessions() {
        return sessions;
    }

    public FirecrackerSandboxTerminal firecrackerTerminal() {
        return terminal;
    }

    public FirecrackerSandboxFileSystem firecrackerFileSystem() {
        return fileSystem;
    }

    public FirecrackerSandboxPorts firecrackerPorts() {
        return ports;
    }

    public FirecrackerSandboxDisplay firecrackerDisplay() {
        return display;
    }

    public FirecrackerSandboxArtifacts firecrackerArtifacts() {
        return artifacts;
    }

    public FirecrackerSandboxComputer firecrackerComputer() {
        return computer;
    }
}
