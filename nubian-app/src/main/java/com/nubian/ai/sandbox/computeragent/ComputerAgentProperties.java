package com.nubian.ai.sandbox.computeragent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the computer-agent HTTP client.
 * Bind with prefix {@code nubian.sandbox.computer-agent}.
 */
@ConfigurationProperties(prefix = "nubian.sandbox.computer-agent")
public class ComputerAgentProperties {

    /** Enable the auto-configured {@link ComputerAgentClient} bean. */
    private boolean enabled = false;

    /** Hostname or IP of the Ubuntu-desktop guest. Required when enabled. */
    private String host;

    /** Port the guest agent listens on. Default: 6090. */
    private int agentPort = 6090;

    /** VNC server port on the guest. Default: 5900. */
    private int vncPort = 5900;

    /** noVNC web-proxy port on the guest. Default: 6080. */
    private int novncPort = 6080;

    /** Chrome DevTools Protocol port on the guest. Default: 9222. */
    private int cdpPort = 9222;

    /**
     * Optional base path prepended to every agent request. Useful when the
     * agent is published behind a reverse proxy under a path prefix
     * (e.g. {@code /agent} when nginx routes
     * {@code http://host:port/agent/...} to the guest's internal
     * {@code :6090/...}). Default: empty (no prefix).
     */
    private String basePath = "";

    /** TCP connect timeout. Default: 5 s. */
    private Duration connectTimeout = Duration.parse("PT5S");

    /** Per-request timeout. Default: 60 s. */
    private Duration requestTimeout = Duration.parse("PT60S");

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public ComputerAgentEndpoints toEndpoints() {
        return new ComputerAgentEndpoints(host, agentPort, vncPort, novncPort, cdpPort, basePath);
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getAgentPort() { return agentPort; }
    public void setAgentPort(int agentPort) { this.agentPort = agentPort; }

    public int getVncPort() { return vncPort; }
    public void setVncPort(int vncPort) { this.vncPort = vncPort; }

    public int getNovncPort() { return novncPort; }
    public void setNovncPort(int novncPort) { this.novncPort = novncPort; }

    public int getCdpPort() { return cdpPort; }
    public void setCdpPort(int cdpPort) { this.cdpPort = cdpPort; }

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath == null ? "" : basePath; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
