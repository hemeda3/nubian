package com.nubian.ai.sandbox.computeragent.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.sandbox.api.SandboxComputer;
import com.nubian.ai.sandbox.computeragent.ComputerAgentClient;
import com.nubian.ai.sandbox.computeragent.ComputerAgentException;
import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import com.nubian.ai.sandbox.model.SandboxComputerEnvironment;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@link SandboxComputer} adapter that proxies to a Ubuntu-desktop guest agent
 * via {@link ComputerAgentClient}.
 *
 * <p>Inspect probes the agent's /eyes/screenshot and /eyes/accessibility endpoints
 * to confirm the desktop stack is live, then assembles a {@link SandboxComputerEnvironment}
 * describing the available capabilities.
 *
 * <p>No Spring, no Lombok — pure POJO. Bean wiring is Stream D's responsibility.
 */
public class ComputerAgentComputer implements SandboxComputer {

    static final String PROVIDER_ID_DEFAULT = "computer-agent";

    private final String providerId;
    private final ComputerAgentClient client;
    private final ObjectMapper mapper;

    public ComputerAgentComputer(
            String providerId,
            ComputerAgentClient client,
            ObjectMapper mapper) {
        this.providerId = providerId == null ? PROVIDER_ID_DEFAULT : providerId;
        this.client = client;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------------
    // SandboxCapability
    // -------------------------------------------------------------------------

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public SandboxCapabilityType type() {
        return SandboxCapabilityType.COMPUTER;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("runtime", "computer-agent", "contract", "ubuntu-desktop-guest");
    }

    // -------------------------------------------------------------------------
    // SandboxComputer
    // -------------------------------------------------------------------------

    /**
     * Probes the guest agent to build a {@link SandboxComputerEnvironment}.
     *
     * <p>Calls GET /eyes/screenshot to verify the display stack is reachable,
     * then GET /eyes/accessibility for UI tree availability. Falls back
     * gracefully if either endpoint is unavailable.
     */
    @Override
    public CompletableFuture<SandboxComputerEnvironment> inspect(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            boolean screenshotAvailable = false;
            boolean accessibilityAvailable = false;
            String screenshotError = "";
            String accessibilityError = "";

            try {
                client.screenshot();
                screenshotAvailable = true;
            } catch (ComputerAgentException e) {
                screenshotError = e.getMessage();
            }

            try {
                client.accessibility();
                accessibilityAvailable = true;
            } catch (ComputerAgentException e) {
                accessibilityError = e.getMessage();
            }

            return buildEnvironment(sessionId, screenshotAvailable, screenshotError,
                    accessibilityAvailable, accessibilityError);
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private SandboxComputerEnvironment buildEnvironment(
            String sessionId,
            boolean screenshotAvailable,
            String screenshotError,
            boolean accessibilityAvailable,
            String accessibilityError) {

        List<SandboxComputerEnvironment.Tool> tools = List.of(
                tool("screenshot-pyautogui", "eyes", List.of("pyautogui"),
                        screenshotAvailable, "pyautogui",
                        screenshotAvailable ? "" : screenshotError),
                tool("accessibility-atspy", "eyes", List.of("atspy", "pyatspi"),
                        accessibilityAvailable, "atspy",
                        accessibilityAvailable ? "" : accessibilityError),
                tool("mouse-keyboard-pyautogui", "hands", List.of("pyautogui"),
                        screenshotAvailable, "pyautogui", ""),
                tool("browser-chromium", "browser", List.of("chromium", "google-chrome"),
                        true, "chromium", ""),
                tool("xdotool", "hands", List.of("xdotool"), true, "xdotool", ""),
                tool("python", "language", List.of("python3"), true, "python3", "")
        );

        List<SandboxComputerEnvironment.Directory> directories = List.of(
                directory("workspace", "/workspace", "user files and generated output"),
                directory("downloads", "/downloads", "browser downloads"),
                directory("uploads", "/uploads", "user uploaded files"),
                directory("logs", "/logs", "screenshots, actions, and terminal output"),
                directory("agent-tmp", "/tmp/agent", "scratchpad, screenshots, browser DOM")
        );

        List<SandboxComputerEnvironment.Feature> features = List.of(
                feature("eyes", "computer", "Screenshots and visual capture", screenshotAvailable,
                        "screenshot=" + screenshotAvailable),
                feature("eyes-accessibility", "computer", "Accessibility tree inspection",
                        accessibilityAvailable, "accessibility=" + accessibilityAvailable),
                feature("hands", "computer", "Mouse and keyboard via pyautogui", screenshotAvailable,
                        "pyautogui=" + screenshotAvailable),
                feature("browser", "computer", "CDP-controlled Chromium browser", true, "cdp=true"),
                feature("internet", "network", "Outbound network from guest", true, "nat=true")
        );

        SandboxComputerEnvironment.ResourceLimits limits = new SandboxComputerEnvironment.ResourceLimits(
                "", "", "", "", "", "", "guest-nat", Map.of());

        Map<String, String> meta = Map.of(
                "computer-agent.screenshot", Boolean.toString(screenshotAvailable),
                "computer-agent.accessibility", Boolean.toString(accessibilityAvailable)
        );

        return new SandboxComputerEnvironment(
                providerId,
                sessionId,
                Instant.now(),
                "ubuntu-desktop-guest",
                "linux",
                features,
                tools,
                directories,
                List.of(),
                limits,
                meta
        );
    }

    private static SandboxComputerEnvironment.Tool tool(
            String id, String category, List<String> commands,
            boolean available, String detected, String version) {
        return new SandboxComputerEnvironment.Tool(
                id, category, commands, available, detected, "", version, Map.of());
    }

    private static SandboxComputerEnvironment.Directory directory(
            String id, String path, String purpose) {
        return new SandboxComputerEnvironment.Directory(id, path, purpose, true, Map.of());
    }

    private static SandboxComputerEnvironment.Feature feature(
            String id, String category, String name, boolean available, String detail) {
        return new SandboxComputerEnvironment.Feature(id, category, name, available, detail, Map.of());
    }

    // -------------------------------------------------------------------------
    // Exception → SandboxFailure conversion (static utility used by tests)
    // -------------------------------------------------------------------------

    static SandboxFailure toFailure(
            String providerId, String sessionId, String operation, ComputerAgentException e) {
        return SandboxFailure.of(providerId, sessionId, SandboxFailureCode.COMMAND_ERROR,
                e.getMessage(), operation);
    }
}
