package com.nubian.ai.sandbox.computeragent;

import com.nubian.ai.sandbox.api.SandboxArtifacts;
import com.nubian.ai.sandbox.api.SandboxBrowser;
import com.nubian.ai.sandbox.api.SandboxComputer;
import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.api.SandboxPorts;
import com.nubian.ai.sandbox.api.SandboxProvider;
import com.nubian.ai.sandbox.api.SandboxSessionService;
import com.nubian.ai.sandbox.api.SandboxTerminal;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentDisplay;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentPorts;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SandboxProvider} implementation for the Ubuntu-desktop computer-agent backend.
 *
 * <p>The backend is always-up (OSWorld / FlyVM single-VM model): all session IDs map to the
 * same guest configured by {@link ComputerAgentProperties#getHost()}.
 */
public class ComputerAgentSandboxProvider implements SandboxProvider {

    public static final String PROVIDER_ID = "computer-agent";

    private final ComputerAgentSandboxSessionService sessions;
    private final ComputerAgentDisplay display;
    private final ComputerAgentPorts ports;

    // Optional capability adapters supplied by the beans configuration (Streams B + C).
    private final Optional<SandboxComputer> computer;
    private final Optional<SandboxBrowser> browser;
    private final Optional<SandboxTerminal> terminal;
    private final Optional<SandboxFileSystem> fileSystem;
    private final Optional<SandboxArtifacts> artifacts;

    /** Minimal constructor — only core session/display/ports capabilities. */
    public ComputerAgentSandboxProvider(
            ComputerAgentSandboxSessionService sessions,
            ComputerAgentDisplay display,
            ComputerAgentPorts ports) {
        this(sessions, display, ports, null, null, null, null, null);
    }

    /** Full constructor — all optional capability adapters may be null. */
    public ComputerAgentSandboxProvider(
            ComputerAgentSandboxSessionService sessions,
            ComputerAgentDisplay display,
            ComputerAgentPorts ports,
            SandboxComputer computer,
            SandboxBrowser browser,
            SandboxTerminal terminal,
            SandboxFileSystem fileSystem,
            SandboxArtifacts artifacts) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.display = Objects.requireNonNull(display, "display");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.computer = Optional.ofNullable(computer);
        this.browser = Optional.ofNullable(browser);
        this.terminal = Optional.ofNullable(terminal);
        this.fileSystem = Optional.ofNullable(fileSystem);
        this.artifacts = Optional.ofNullable(artifacts);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Computer Agent (Ubuntu Desktop)";
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "provider.id", PROVIDER_ID,
                "implementation", "computer-agent-http",
                "runtime", "ubuntu-desktop",
                "sessionModel", "single-guest");
    }

    @Override
    public SandboxSessionService sessions() {
        return sessions;
    }

    @Override
    public Optional<SandboxDisplay> display() {
        return Optional.of(display);
    }

    @Override
    public Optional<SandboxPorts> ports() {
        return Optional.of(ports);
    }

    @Override
    public Optional<SandboxComputer> computer() {
        return computer;
    }

    @Override
    public Optional<SandboxBrowser> browser() {
        return browser;
    }

    @Override
    public Optional<SandboxTerminal> terminal() {
        return terminal;
    }

    @Override
    public Optional<SandboxFileSystem> fileSystem() {
        return fileSystem;
    }

    @Override
    public Optional<SandboxArtifacts> artifacts() {
        return artifacts;
    }
}
