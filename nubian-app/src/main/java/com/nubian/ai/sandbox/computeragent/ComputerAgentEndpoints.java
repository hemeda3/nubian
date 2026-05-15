package com.nubian.ai.sandbox.computeragent;

/**
 * Holds the resolved network coordinates for a single Ubuntu-desktop guest.
 * Immutable value object — construct once per sandbox session.
 */
public record ComputerAgentEndpoints(
        String host,
        int agentPort,
        int vncPort,
        int novncPort,
        int cdpPort,
        String basePath
) {
    public ComputerAgentEndpoints(String host, int agentPort, int vncPort, int novncPort, int cdpPort) {
        this(host, agentPort, vncPort, novncPort, cdpPort, "");
    }

    public ComputerAgentEndpoints {
        basePath = normalizeBasePath(basePath);
    }

    public String agentBaseUrl() { return "http://" + host + ":" + agentPort + basePath; }
    public String vncUrl()       { return "vnc://" + host + ":" + vncPort; }
    public String novncUrl()     { return "http://" + host + ":" + novncPort; }
    public String cdpBaseUrl()   { return "http://" + host + ":" + cdpPort; }

    private static String normalizeBasePath(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) return "";
        if (!trimmed.startsWith("/")) trimmed = "/" + trimmed;
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }
}
