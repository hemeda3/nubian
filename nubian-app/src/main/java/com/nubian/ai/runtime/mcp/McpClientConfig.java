package com.nubian.ai.runtime.mcp;

import com.nubian.ai.runtime.mcp.auth.McpTokenStore;
import com.nubian.ai.runtime.mcp.lifecycle.ClientCapabilities;
import com.nubian.ai.runtime.mcp.lifecycle.ClientInfo;

import java.time.Duration;

/**
 * Immutable configuration for an {@link McpClient} instance.
 *
 * @param connectTimeout      Maximum time allowed for the transport to establish a
 *                            connection before the initialize call times out.
 * @param requestTimeout      Default timeout applied to every JSON-RPC request sent
 *                            through this client.
 * @param heartbeatCadence    Interval between ping heartbeats when
 *                            {@code autoStartHeartbeat} is {@code true}.
 * @param autoStartHeartbeat  Whether to automatically start the ping heartbeat
 *                            after successful initialization.
 * @param capabilities        Capabilities this client advertises during {@code initialize}.
 * @param clientInfo          Metadata about this client application.
 * @param tokenStore          OAuth token store used for HTTP transports that require bearer auth.
 */
public record McpClientConfig(
        Duration connectTimeout,
        Duration requestTimeout,
        Duration heartbeatCadence,
        boolean autoStartHeartbeat,
        ClientCapabilities capabilities,
        ClientInfo clientInfo,
        McpTokenStore tokenStore
) {

    /**
     * Returns a sensible default configuration for the Nubian framework:
     * <ul>
     *   <li>10 s connect timeout</li>
     *   <li>60 s request timeout</li>
     *   <li>30 s heartbeat cadence (safely under the 5-minute cache-window boundary)</li>
     *   <li>autoStartHeartbeat = true</li>
     *   <li>Nubian default capabilities and client info</li>
     *   <li>In-memory token store</li>
     * </ul>
     */
    public static McpClientConfig defaults() {
        return new McpClientConfig(
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                true,
                NubianMcpDefaults.defaultCapabilities(),
                NubianMcpDefaults.nubianClientInfo(),
                new McpTokenStore.InMemoryTokenStore()
        );
    }
}
