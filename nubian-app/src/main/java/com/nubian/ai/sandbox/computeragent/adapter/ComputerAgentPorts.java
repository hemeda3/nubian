package com.nubian.ai.sandbox.computeragent.adapter;

import com.nubian.ai.sandbox.api.SandboxPorts;
import com.nubian.ai.sandbox.computeragent.ComputerAgentEndpoints;
import com.nubian.ai.sandbox.computeragent.ComputerAgentSandboxException;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import com.nubian.ai.sandbox.model.SandboxPort;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link SandboxPorts} adapter for the computer-agent backend.
 *
 * <p>Publishes four fixed ports that are always available on the Ubuntu-desktop guest:
 * <ul>
 *   <li>6090 — guest HTTP agent API</li>
 *   <li>6080 — noVNC web proxy</li>
 *   <li>5900 — VNC server</li>
 *   <li>9222 — Chrome DevTools Protocol (CDP)</li>
 * </ul>
 * Ports are statically resolved from {@link ComputerAgentEndpoints}; no dynamic lifecycle calls
 * are made to the backend.
 */
public class ComputerAgentPorts implements SandboxPorts {

    private final String providerId;
    private final ComputerAgentEndpoints endpoints;

    public ComputerAgentPorts(String providerId, ComputerAgentEndpoints endpoints) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        this.providerId = providerId;
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "provider.id", providerId,
                "implementation", "computer-agent-fixed-ports");
    }

    /**
     * Returns the requested port if it matches one of the four fixed ports; otherwise fails.
     */
    @Override
    public CompletableFuture<SandboxPort> exposePort(
            String sessionId,
            int port,
            String protocol,
            boolean publicAccess) {

        return listPorts(sessionId).thenApply(ports -> ports.stream()
                .filter(p -> p.port() == port)
                .findFirst()
                .orElseThrow(() -> new ComputerAgentSandboxException(new SandboxFailure(
                        providerId,
                        sessionId == null ? "" : sessionId,
                        SandboxFailureCode.UNSUPPORTED_CAPABILITY,
                        "Computer-agent provider only exposes fixed ports 5900, 6080, 6090, and 9222",
                        "ports.exposePort",
                        false,
                        Map.of("port", Integer.toString(port))))));
    }

    /**
     * Lists the four statically known ports for the Ubuntu-desktop guest.
     */
    @Override
    public CompletableFuture<List<SandboxPort>> listPorts(String sessionId) {
        String sid = sessionId == null ? "" : sessionId;
        return CompletableFuture.completedFuture(List.of(
                port(sid, endpoints.agentPort(), "http", endpoints.agentBaseUrl(), "agent"),
                port(sid, endpoints.novncPort(), "http", endpoints.novncUrl(), "novnc"),
                port(sid, endpoints.vncPort(), "vnc", endpoints.vncUrl(), "vnc"),
                port(sid, endpoints.cdpPort(), "http", endpoints.cdpBaseUrl(), "cdp")));
    }

    /**
     * Closing fixed guest ports is not supported.
     */
    @Override
    public CompletableFuture<Void> closePort(String sessionId, int port) {
        return CompletableFuture.failedFuture(new ComputerAgentSandboxException(new SandboxFailure(
                providerId,
                sessionId == null ? "" : sessionId,
                SandboxFailureCode.UNSUPPORTED_CAPABILITY,
                "Computer-agent fixed guest ports are tied to the session lifecycle",
                "ports.closePort",
                false,
                Map.of("port", Integer.toString(port)))));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SandboxPort port(String sessionId, int guestPort, String protocol, String url, String label) {
        Map<String, String> meta = Map.of(
                "computer-agent.host", endpoints.host(),
                "computer-agent.portLabel", label,
                "computer-agent.guestPort", Integer.toString(guestPort));
        return new SandboxPort(
                providerId,
                sessionId,
                guestPort,
                protocol,
                url == null || url.isBlank() ? null : URI.create(url),
                false,
                meta);
    }
}
