package com.nubian.ai.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.client.ElicitationProvider;
import com.nubian.ai.runtime.mcp.client.RootsProvider;
import com.nubian.ai.runtime.mcp.client.SamplingProvider;
import com.nubian.ai.runtime.mcp.client.StaticRootsProvider;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.List;

/**
 * Spring Boot auto-configuration for the Nubian MCP client stack.
 *
 * <p>Registers the following beans unless already present in the application context:
 * <ul>
 *   <li>{@link ObjectMapper} — pre-configured for MCP JSON-RPC serialization</li>
 *   <li>{@link McpServerRegistry} — registry of active MCP client connections</li>
 *   <li>{@link RootsProvider} — empty static roots provider (default)</li>
 *   <li>{@link ElicitationProvider} — auto-decline elicitation provider (default)</li>
 *   <li>{@link SamplingProvider} — not-configured sampling provider (default)</li>
 *   <li>{@link ApplicationRunner} — auto-starts servers listed under
 *       {@code nubian.mcp.servers} when {@code nubian.mcp.auto-start=true}</li>
 *   <li>{@link McpHealthIndicator} — Actuator health indicator (only when
 *       Spring Boot Actuator is on the classpath)</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
public class McpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpAutoConfiguration.class);

    // -----------------------------------------------------------------------
    // Core beans
    // -----------------------------------------------------------------------

    /**
     * MCP-configured {@link ObjectMapper} bean.
     *
     * <p>If the application already declares an {@code ObjectMapper} bean, that bean
     * is used instead. Applications that need a separate MCP mapper should use
     * {@link McpJsonMapper#instance()} directly.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper mcpJsonMapper() {
        return McpJsonMapper.instance();
    }

    /** Default {@link McpServerRegistry} bean. */
    @Bean
    @ConditionalOnMissingBean
    public McpServerRegistry mcpServerRegistry() {
        return new McpServerRegistry();
    }

    // -----------------------------------------------------------------------
    // Default provider beans
    // -----------------------------------------------------------------------

    /** Default {@link RootsProvider}: empty list, no roots exposed. */
    @Bean
    @ConditionalOnMissingBean
    public RootsProvider defaultRootsProvider() {
        return new StaticRootsProvider(List.of());
    }

    /** Default {@link ElicitationProvider}: auto-declines all server elicitation requests. */
    @Bean
    @ConditionalOnMissingBean
    public ElicitationProvider defaultElicitationProvider() {
        return ElicitationProvider.AutoDeclineElicitationProvider.INSTANCE;
    }

    /** Default {@link SamplingProvider}: rejects all server sampling requests. */
    @Bean
    @ConditionalOnMissingBean
    public SamplingProvider defaultSamplingProvider() {
        return SamplingProvider.NotConfiguredSamplingProvider.INSTANCE;
    }

    // -----------------------------------------------------------------------
    // Auto-start runner
    // -----------------------------------------------------------------------

    /**
     * {@link ApplicationRunner} that auto-starts MCP servers defined in
     * {@link McpServerProperties} when {@code nubian.mcp.auto-start=true}.
     *
     * <p>Server startup failures are logged and swallowed so that a single missing
     * optional MCP server does not prevent the rest of the application from starting.
     */
    @Bean
    @ConditionalOnProperty(prefix = "nubian.mcp", name = "auto-start", havingValue = "true",
            matchIfMissing = false)
    public ApplicationRunner mcpServerStarter(
            McpServerRegistry registry,
            McpServerProperties props,
            RootsProvider roots,
            SamplingProvider sampling,
            ElicitationProvider elicitation) {

        return args -> {
            List<McpServerProperties.ServerSpec> servers = props.getServers();
            if (servers == null || servers.isEmpty()) {
                log.debug("nubian.mcp.auto-start=true but no servers configured — nothing to start");
                return;
            }

            for (McpServerProperties.ServerSpec spec : servers) {
                try {
                    McpClient client = buildClientFromSpec(spec, roots, sampling, elicitation);
                    registry.register(spec.name(), client);
                    log.info("Auto-started MCP server '{}'", spec.name());
                } catch (Exception ex) {
                    log.error("Failed to auto-start MCP server '{}': {} — skipping",
                            spec.name(), ex.getMessage(), ex);
                }
            }
        };
    }

    // -----------------------------------------------------------------------
    // Health indicator (Actuator-conditional)
    // -----------------------------------------------------------------------

    /**
     * MCP health indicator bean — only registered when Spring Boot Actuator is present.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @ConditionalOnMissingBean(name = "mcpHealthIndicator")
    public McpHealthIndicator mcpHealthIndicator(McpServerRegistry registry) {
        return new McpHealthIndicator(registry);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private McpClient buildClientFromSpec(
            McpServerProperties.ServerSpec spec,
            RootsProvider roots,
            SamplingProvider sampling,
            ElicitationProvider elicitation) {

        McpClientBuilder builder = McpClientBuilder.builder()
                .withRootsProvider(roots)
                .withSamplingProvider(sampling)
                .withElicitationProvider(elicitation);

        String type = spec.type();
        if (type == null) {
            throw new IllegalArgumentException(
                    "Server spec '" + spec.name() + "' is missing 'type' (expected 'stdio' or 'http')");
        }

        switch (type.toLowerCase()) {
            case "stdio" -> {
                if (spec.command() == null || spec.command().isBlank()) {
                    throw new IllegalArgumentException(
                            "Server spec '" + spec.name() + "' type=stdio requires 'command'");
                }
                List<String> cmd = new java.util.ArrayList<>();
                cmd.add(spec.command());
                if (spec.args() != null) {
                    cmd.addAll(spec.args());
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                if (spec.env() != null && !spec.env().isEmpty()) {
                    pb.environment().putAll(spec.env());
                }
                builder.stdio(pb);
            }
            case "http" -> {
                if (spec.url() == null || spec.url().isBlank()) {
                    throw new IllegalArgumentException(
                            "Server spec '" + spec.name() + "' type=http requires 'url'");
                }
                builder.http(URI.create(spec.url()));
            }
            default -> throw new IllegalArgumentException(
                    "Unknown MCP server type '" + type + "' for server '" + spec.name()
                    + "'. Expected 'stdio' or 'http'.");
        }

        return builder.build();
    }
}
