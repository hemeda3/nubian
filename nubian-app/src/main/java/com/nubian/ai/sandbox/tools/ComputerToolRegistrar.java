package com.nubian.ai.sandbox.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.tool.ToolRegistry;
import com.nubian.ai.sandbox.registry.SandboxRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Registers high-level computer wrappers into the runtime tool registry.
 */
@Component
public class ComputerToolRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(ComputerToolRegistrar.class);

    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final ObjectProvider<SandboxRegistry> sandboxRegistryProvider;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration invocationTimeout;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public ComputerToolRegistrar(
            ObjectProvider<ToolRegistry> toolRegistryProvider,
            ObjectProvider<SandboxRegistry> sandboxRegistryProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            @Value("${nubian.sandbox.tools.computer-tool-enabled:true}") boolean enabled,
            @Value("${nubian.sandbox.tools.computer-tool-timeout-seconds:900}") long timeoutSeconds) {
        this.toolRegistryProvider = toolRegistryProvider;
        this.sandboxRegistryProvider = sandboxRegistryProvider;
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.enabled = enabled;
        this.invocationTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnApplicationReady() {
        if (!enabled || !registered.compareAndSet(false, true)) {
            return;
        }
        register();
    }

    public boolean register() {
        ToolRegistry toolRegistry = toolRegistryProvider.getIfAvailable();
        SandboxRegistry sandboxRegistry = sandboxRegistryProvider.getIfAvailable();
        if (toolRegistry == null || sandboxRegistry == null) {
            logger.debug("Skipping computer tool registration because ToolRegistry or SandboxRegistry is unavailable");
            return false;
        }

        toolRegistry.registerTool(
                new ComputerTool(objectMapper, sandboxRegistry, invocationTimeout),
                ComputerTool.FUNCTION_NAMES);
        logger.info("Registered {} high-level computer tools with Nubian runtime", ComputerTool.FUNCTION_NAMES.size());
        return true;
    }
}
