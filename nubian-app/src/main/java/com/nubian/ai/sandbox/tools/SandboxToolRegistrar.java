package com.nubian.ai.sandbox.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.tool.Tool;
import com.nubian.ai.runtime.tool.ToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Spring-managed bridge from sandbox capability providers to the Nubian runtime tool registry.
 *
 * <p>Auto-discovery is intentionally optional. If no
 * {@code com.nubian.ai.sandbox.api.*} providers are present, registration is a
 * no-op.</p>
 */
@Component
public class SandboxToolRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(SandboxToolRegistrar.class);
    private static final String SANDBOX_API_PACKAGE = "com.nubian.ai.sandbox.api";
    private static final Set<String> SANDBOX_API_SIMPLE_NAMES = Set.of(
            "SandboxProvider",
            "SandboxCapability",
            "SandboxSessionService",
            "SandboxFileSystem",
            "SandboxTerminal",
            "SandboxBrowser",
            "SandboxDisplay",
            "SandboxPorts",
            "SandboxArtifacts",
            "SandboxComputer");
    private static final List<String> PROVIDER_TYPE_NAMES = List.of(
            "com.nubian.ai.sandbox.api.SandboxCapabilityProvider",
            "com.nubian.ai.sandbox.api.CapabilityProvider",
            "com.nubian.ai.sandbox.api.SandboxToolProvider",
            "com.nubian.ai.sandbox.api.ToolProvider");

    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final ToolRegistry directToolRegistry;
    private final ListableBeanFactory beanFactory;
    private final ObjectMapper objectMapper;
    private final boolean autoRegistrationEnabled;
    private final Duration invocationTimeout;
    private final Collection<?> directProviders;
    private final AtomicBoolean startupRegistrationAttempted = new AtomicBoolean(false);

    @Autowired
    public SandboxToolRegistrar(
            ObjectProvider<ToolRegistry> toolRegistryProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ListableBeanFactory beanFactory,
            @Value("${nubian.sandbox.tools.auto-registration-enabled:true}") boolean autoRegistrationEnabled,
            @Value("${nubian.sandbox.tools.invocation-timeout-seconds:900}") long invocationTimeoutSeconds) {
        this.toolRegistryProvider = toolRegistryProvider;
        this.directToolRegistry = null;
        this.beanFactory = beanFactory;
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.autoRegistrationEnabled = autoRegistrationEnabled;
        this.invocationTimeout = Duration.ofSeconds(Math.max(1, invocationTimeoutSeconds));
        this.directProviders = List.of();
    }

    public SandboxToolRegistrar(ToolRegistry toolRegistry, ObjectMapper objectMapper, Collection<?> providers) {
        this.toolRegistryProvider = null;
        this.directToolRegistry = toolRegistry;
        this.beanFactory = null;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.autoRegistrationEnabled = false;
        this.invocationTimeout = Duration.ofMinutes(15);
        this.directProviders = providers == null ? List.of() : List.copyOf(providers);
    }

    public SandboxToolRegistrar(ToolRegistry toolRegistry, Collection<?> providers) {
        this(toolRegistry, new ObjectMapper(), providers);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerOnApplicationReady() {
        if (!autoRegistrationEnabled || !startupRegistrationAttempted.compareAndSet(false, true)) {
            return;
        }
        registerDiscoveredCapabilities();
    }

    public int registerDiscoveredCapabilities() {
        return registerDiscoveredCapabilities(null);
    }

    public int registerForScope(String scopeId) {
        return registerDiscoveredCapabilities(scopeId);
    }

    public int registerDiscoveredCapabilities(String scopeId) {
        List<Object> providers = new ArrayList<>(directProviders);
        providers.addAll(discoverProviderBeans());
        return registerCapabilities(scopeId, providers);
    }

    public int registerCapabilities(String scopeId, Collection<?> providersOrCapabilities) {
        ToolRegistry registry = toolRegistry();
        if (registry == null) {
            logger.debug("Skipping sandbox capability registration because ToolRegistry is not available");
            return 0;
        }

        List<SandboxCapabilityAdapter> capabilities = adaptCapabilities(providersOrCapabilities);
        if (capabilities.isEmpty()) {
            logger.debug("No sandbox capability providers discovered; skipping Nubian runtime tool registration");
            return 0;
        }

        SandboxCapabilityTool tool = new SandboxCapabilityTool(capabilities, objectMapper, invocationTimeout);
        if (scopeId == null || scopeId.isBlank()) {
            registry.registerTool(tool, List.of(SandboxCapabilityTool.FUNCTION_NAME));
        } else {
            registry.registerToolForScope(scopeId, tool, List.of(SandboxCapabilityTool.FUNCTION_NAME));
        }

        logger.info(
                "Registered {} sandbox capabilities with Nubian runtime{}",
                capabilities.size(),
                scopeId == null || scopeId.isBlank() ? "" : " for scope " + scopeId);
        return capabilities.size();
    }

    public List<String> discoverCapabilityNames() {
        List<Object> providers = new ArrayList<>(directProviders);
        providers.addAll(discoverProviderBeans());
        return adaptCapabilities(providers).stream()
                .map(SandboxCapabilityAdapter::name)
                .distinct()
                .toList();
    }

    private ToolRegistry toolRegistry() {
        if (directToolRegistry != null) {
            return directToolRegistry;
        }
        return toolRegistryProvider == null ? null : toolRegistryProvider.getIfAvailable();
    }

    private List<Object> discoverProviderBeans() {
        if (beanFactory == null) {
            return List.of();
        }

        Set<String> beanNames = new LinkedHashSet<>();
        for (String typeName : PROVIDER_TYPE_NAMES) {
            resolveClass(typeName).ifPresent(type -> {
                try {
                    beanNames.addAll(List.of(beanFactory.getBeanNamesForType(type, false, false)));
                } catch (BeansException ex) {
                    logger.debug("Failed to query sandbox provider type {}: {}", typeName, ex.getMessage());
                }
            });
        }

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            try {
                Class<?> type = beanFactory.getType(beanName, false);
                if (type != null && isSandboxApiCandidate(type)) {
                    beanNames.add(beanName);
                }
            } catch (BeansException ex) {
                logger.debug("Failed to inspect bean {} for sandbox capability API: {}", beanName, ex.getMessage());
            }
        }

        List<Object> providers = new ArrayList<>();
        for (String beanName : beanNames) {
            try {
                Object bean = beanFactory.getBean(beanName);
                if (!isBridgeInfrastructure(bean)) {
                    providers.add(bean);
                }
            } catch (BeansException ex) {
                logger.warn("Failed to load sandbox capability provider bean {}: {}", beanName, ex.getMessage());
            }
        }
        return providers;
    }

    private static java.util.Optional<Class<?>> resolveClass(String className) {
        try {
            return java.util.Optional.of(ClassUtils.forName(className, SandboxToolRegistrar.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError ex) {
            return java.util.Optional.empty();
        }
    }

    private static boolean isSandboxApiCandidate(Class<?> rawType) {
        Class<?> type = ClassUtils.getUserClass(rawType);
        if (isSandboxApiType(type)) {
            return true;
        }
        for (Class<?> interfaceType : allInterfaces(type)) {
            if (isSandboxApiType(interfaceType)) {
                return true;
            }
        }
        Class<?> superclass = type.getSuperclass();
        return superclass != null && !Object.class.equals(superclass) && isSandboxApiCandidate(superclass);
    }

    private static boolean isSandboxApiType(Class<?> type) {
        Package typePackage = type.getPackage();
        String packageName = typePackage == null ? "" : typePackage.getName();
        return packageName.startsWith(SANDBOX_API_PACKAGE)
                && (SANDBOX_API_SIMPLE_NAMES.contains(type.getSimpleName())
                || type.getSimpleName().contains("Capability")
                || type.getSimpleName().contains("Tool"));
    }

    private static Set<Class<?>> allInterfaces(Class<?> type) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        Class<?> current = type;
        while (current != null && !Object.class.equals(current)) {
            collectInterfaces(current, interfaces);
            current = current.getSuperclass();
        }
        return interfaces;
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> interfaces) {
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (interfaces.add(interfaceType)) {
                collectInterfaces(interfaceType, interfaces);
            }
        }
    }

    private static boolean isBridgeInfrastructure(Object bean) {
        return bean instanceof SandboxToolRegistrar
                || bean instanceof ComputerToolRegistrar
                || bean instanceof ComputerTool
                || bean instanceof SandboxCapabilityTool
                || bean instanceof SandboxCapabilityAdapter
                || bean instanceof ToolRegistry
                || bean instanceof Tool
                || bean instanceof ObjectMapper;
    }

    private static List<SandboxCapabilityAdapter> adaptCapabilities(Collection<?> providersOrCapabilities) {
        if (providersOrCapabilities == null || providersOrCapabilities.isEmpty()) {
            return List.of();
        }
        Map<String, SandboxCapabilityAdapter> unique = new LinkedHashMap<>();
        for (Object providerOrCapability : providersOrCapabilities) {
            for (SandboxCapabilityAdapter adapter : SandboxCapabilityAdapter.adaptAll(providerOrCapability)) {
                unique.putIfAbsent(adapter.name(), adapter);
            }
        }
        return List.copyOf(unique.values());
    }
}
