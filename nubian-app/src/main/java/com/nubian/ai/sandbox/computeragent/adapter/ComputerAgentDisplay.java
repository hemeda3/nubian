package com.nubian.ai.sandbox.computeragent.adapter;

import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.computeragent.ComputerAgentClient;
import com.nubian.ai.sandbox.computeragent.ComputerAgentEndpoints;
import com.nubian.ai.sandbox.model.SandboxDisplayFrame;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link SandboxDisplay} adapter for the computer-agent backend.
 *
 * <p>Returns the VNC / noVNC URLs that are statically resolved from
 * {@link ComputerAgentEndpoints}. Actual framebuffer capture is delegated to
 * the higher-level {@code ComputerAgentComputer} (Stream B); this adapter
 * handles URL exposure and resize signalling only.
 */
public class ComputerAgentDisplay implements SandboxDisplay {

    private static final Logger log = LoggerFactory.getLogger(ComputerAgentDisplay.class);

    private final String providerId;
    private final ComputerAgentEndpoints endpoints;
    private final ComputerAgentClient client;

    public ComputerAgentDisplay(String providerId, ComputerAgentEndpoints endpoints) {
        this(providerId, endpoints, null);
    }

    public ComputerAgentDisplay(
            String providerId, ComputerAgentEndpoints endpoints, ComputerAgentClient client) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        this.providerId = providerId;
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.client = client;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "provider.id", providerId,
                "implementation", "computer-agent-vnc-url",
                "computer-agent.vncUrl", endpoints.vncUrl(),
                "computer-agent.novncUrl", endpoints.novncUrl());
    }

    /**
     * Captures a PNG framebuffer via {@code GET /eyes/screenshot} when a client
     * is wired; otherwise returns an empty-data frame carrying just the VNC /
     * noVNC URL metadata so the demo viewer can still render.
     */
    @Override
    public CompletableFuture<SandboxDisplayFrame> captureFrame(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            byte[] data = new byte[0];
            String source = "computer-agent-vnc-url";
            if (client != null) {
                try {
                    byte[] fetched = client.screenshot();
                    if (fetched != null) {
                        data = fetched;
                        source = "computer-agent-eyes";
                    }
                } catch (RuntimeException ex) {
                    log.warn("captureFrame: /eyes/screenshot failed for session {} — falling back to empty frame: {}",
                            sessionId, ex.toString());
                }
            }
            return new SandboxDisplayFrame(
                    providerId,
                    sessionId == null ? "" : sessionId,
                    0,
                    0,
                    "image/png",
                    data,
                    Instant.now(),
                    Map.of(
                            "computer-agent.vncUrl", endpoints.vncUrl(),
                            "computer-agent.novncUrl", endpoints.novncUrl(),
                            "display.source", source));
        });
    }

    /**
     * Resize is not directly supported through this adapter (requires the guest agent HTTP API
     * in Stream B). Returns an unsupported failure.
     */
    @Override
    public CompletableFuture<Void> resizeDisplay(String sessionId, int width, int height) {
        return CompletableFuture.failedFuture(new com.nubian.ai.sandbox.computeragent.ComputerAgentSandboxException(
                new SandboxFailure(
                        providerId,
                        sessionId == null ? "" : sessionId,
                        SandboxFailureCode.UNSUPPORTED_CAPABILITY,
                        "Display resize requires the ComputerAgentComputer capability",
                        "display.resizeDisplay",
                        false,
                        Map.of())));
    }

    /** The VNC URL for this backend (e.g. {@code vnc://host:5900}). */
    public String vncUrl() {
        return endpoints.vncUrl();
    }

    /** The noVNC URL for this backend (e.g. {@code http://host:6080}). */
    public String novncUrl() {
        return endpoints.novncUrl();
    }
}
