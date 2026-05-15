package com.nubian.ai.sandbox.computeragent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.sandbox.api.SandboxArtifacts;
import com.nubian.ai.sandbox.api.SandboxBrowser;
import com.nubian.ai.sandbox.api.SandboxComputer;
import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.api.SandboxPorts;
import com.nubian.ai.sandbox.api.SandboxProvider;
import com.nubian.ai.sandbox.api.SandboxSessionService;
import com.nubian.ai.sandbox.api.SandboxTerminal;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentArtifacts;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentBrowser;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentComputer;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentDisplay;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentFileSystem;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentPorts;
import com.nubian.ai.sandbox.computeragent.adapter.ComputerAgentTerminal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} that wires the computer-agent sandbox provider beans.
 *
 * <p>This configuration is picked up via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * and is activated only when a {@link ComputerAgentClient} bean is present in the context
 * (registered by Stream A's {@code ComputerAgentAutoConfiguration}).
 *
 * <p>Beans for {@code SandboxComputer}, {@code SandboxBrowser}, {@code SandboxTerminal},
 * {@code SandboxFileSystem}, and {@code SandboxArtifacts} are contributed by Streams B and C.
 * The provider is constructed with those optional adapters resolved via method-parameter
 * injection so Spring injects them when available.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ComputerAgentClient.class)
public class ComputerAgentBeansConfiguration {

    public static final String PROVIDER_ID = "computer-agent";

    // -------------------------------------------------------------------------
    // Core session / display / ports beans (always present when client is up)
    // -------------------------------------------------------------------------

    @Bean
    public ComputerAgentSandboxSessionService computerAgentSessionService(
            ComputerAgentProperties properties) {
        ComputerAgentEndpoints endpoints = properties.toEndpoints();
        return new ComputerAgentSandboxSessionService(PROVIDER_ID, endpoints);
    }

    @Bean
    public SandboxDisplay computerAgentDisplay(
            ComputerAgentProperties properties, ComputerAgentClient client) {
        return new ComputerAgentDisplay(PROVIDER_ID, properties.toEndpoints(), client);
    }

    @Bean
    public SandboxPorts computerAgentPorts(ComputerAgentProperties properties) {
        return new ComputerAgentPorts(PROVIDER_ID, properties.toEndpoints());
    }

    // -------------------------------------------------------------------------
    // Adapters routing through ComputerAgentClient (eyes / hands / shell /
    // memory / browser / artifacts). Each adapter declares
    // {@code providerId() == "computer-agent"} so SandboxRegistry indexes it
    // under the correct provider.
    // -------------------------------------------------------------------------

    @Bean
    public SandboxComputer computerAgentComputer(ComputerAgentClient client, ObjectMapper mapper) {
        return new ComputerAgentComputer(PROVIDER_ID, client, mapper);
    }

    @Bean
    public SandboxBrowser computerAgentBrowser(ComputerAgentClient client, ObjectMapper mapper) {
        return new ComputerAgentBrowser(PROVIDER_ID, client, mapper);
    }

    @Bean
    public SandboxTerminal computerAgentTerminal(ComputerAgentClient client) {
        return new ComputerAgentTerminal(PROVIDER_ID, client);
    }

    @Bean
    public SandboxFileSystem computerAgentFileSystem(ComputerAgentClient client) {
        return new ComputerAgentFileSystem(PROVIDER_ID, client);
    }

    @Bean
    public SandboxArtifacts computerAgentArtifacts(ComputerAgentClient client) {
        return new ComputerAgentArtifacts(PROVIDER_ID, client);
    }

    // -------------------------------------------------------------------------
    // Top-level provider — depends on session service, display, and ports.
    // Optional B/C adapters are injected by Spring when present (method params
    // annotated with @org.springframework.beans.factory.annotation.Autowired(required=false)
    // would be ideal, but @Bean method parameters are always required in Spring unless
    // the type resolves to Optional<T>).  We keep it simple: the provider constructor
    // accepts null for optional adapters, so we use a separate overloaded bean that
    // resolves them lazily from the context via ObjectProvider to avoid hard dependencies.
    // -------------------------------------------------------------------------

    @Bean
    public SandboxProvider computerAgentSandboxProvider(
            ComputerAgentSandboxSessionService sessions,
            SandboxDisplay computerAgentDisplay,
            SandboxPorts computerAgentPorts,
            org.springframework.beans.factory.ObjectProvider<com.nubian.ai.sandbox.api.SandboxComputer> computer,
            org.springframework.beans.factory.ObjectProvider<com.nubian.ai.sandbox.api.SandboxBrowser> browser,
            org.springframework.beans.factory.ObjectProvider<com.nubian.ai.sandbox.api.SandboxTerminal> terminal,
            org.springframework.beans.factory.ObjectProvider<com.nubian.ai.sandbox.api.SandboxFileSystem> fileSystem,
            org.springframework.beans.factory.ObjectProvider<com.nubian.ai.sandbox.api.SandboxArtifacts> artifacts) {

        return new ComputerAgentSandboxProvider(
                sessions,
                (ComputerAgentDisplay) computerAgentDisplay,
                (ComputerAgentPorts) computerAgentPorts,
                computer.getIfAvailable(),
                browser.getIfAvailable(),
                terminal.getIfAvailable(),
                fileSystem.getIfAvailable(),
                artifacts.getIfAvailable());
    }
}
