package com.nubian.ai.runtime.mcp;

import com.nubian.ai.runtime.mcp.lifecycle.ClientCapabilities;
import com.nubian.ai.runtime.mcp.lifecycle.ClientCapabilities.ElicitationCapability;
import com.nubian.ai.runtime.mcp.lifecycle.ClientCapabilities.RootsCapability;
import com.nubian.ai.runtime.mcp.lifecycle.ClientCapabilities.SamplingCapability;
import com.nubian.ai.runtime.mcp.lifecycle.ClientCapabilities.TaskRequestsClient;
import com.nubian.ai.runtime.mcp.lifecycle.ClientCapabilities.TasksCapability;
import com.nubian.ai.runtime.mcp.lifecycle.ClientInfo;

import java.util.Map;

/**
 * Nubian-framework default values for MCP client configuration.
 *
 * <p>These are the baseline capability declarations and client metadata used when
 * no application-specific overrides are provided. Applications may override any
 * individual value by supplying their own {@link ClientCapabilities} or
 * {@link ClientInfo} via {@link McpClientBuilder} or {@link McpClientConfig}.
 */
public final class NubianMcpDefaults {

    /** Semantic version of the Nubian AI Agent Framework. */
    public static final String NUBIAN_VERSION = "0.1.0";

    private NubianMcpDefaults() {}

    /**
     * Returns the default {@link ClientInfo} identifying this framework to MCP servers.
     */
    public static ClientInfo nubianClientInfo() {
        return new ClientInfo(
                "nubian",
                "Nubian AI Agent Framework",
                NUBIAN_VERSION,
                "Java/Spring Boot computer-use agent host",
                null,
                "https://github.com/hemeda3/nubian-ai-agent-framework"
        );
    }

    /**
     * Returns the default {@link ClientCapabilities} declaring everything the Nubian
     * framework supports:
     * <ul>
     *   <li>roots (listChanged=true)</li>
     *   <li>sampling with createMessage method</li>
     *   <li>elicitation with form and url</li>
     *   <li>tasks with list, cancel, and client-side sampling/elicitation requests</li>
     * </ul>
     */
    public static ClientCapabilities defaultCapabilities() {
        RootsCapability roots = new RootsCapability(true);

        SamplingCapability sampling = new SamplingCapability(
                Map.of("createMessage", Map.of()),
                null
        );

        ElicitationCapability elicitation = new ElicitationCapability(
                Map.of(),
                Map.of()
        );

        TaskRequestsClient taskRequests = new TaskRequestsClient(
                Map.of("createMessage", Map.of()),
                Map.of("create", Map.of())
        );

        TasksCapability tasks = new TasksCapability(
                Map.of(),
                Map.of(),
                taskRequests
        );

        return new ClientCapabilities(roots, sampling, elicitation, tasks, null);
    }
}
