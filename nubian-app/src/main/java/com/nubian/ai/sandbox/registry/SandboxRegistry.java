package com.nubian.ai.sandbox.registry;

import com.nubian.ai.sandbox.api.SandboxProvider;
import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SandboxRegistry {
    private static final Logger log = LoggerFactory.getLogger(SandboxRegistry.class);

    private final Map<String, Object> providers;
    private final Map<String, Object> sessions;
    private final Map<String, Object> fileSystems;
    private final Map<String, Object> terminals;
    private final Map<String, Object> browsers;
    private final Map<String, Object> displays;
    private final Map<String, Object> ports;
    private final Map<String, Object> artifacts;
    private final Map<String, Object> computers;
    private final Map<String, ProviderCapabilities> capabilities;
    private final String selectedProviderId;

    public SandboxRegistry(
            List<?> providers,
            List<?> fileSystems,
            List<?> terminals,
            List<?> browsers,
            List<?> displays,
            List<?> ports,
            List<?> artifacts,
            String selectedProviderId) {
        this(providers, List.of(), fileSystems, terminals, browsers, displays, ports, artifacts, List.of(), selectedProviderId);
    }

    public SandboxRegistry(
            List<?> providers,
            List<?> sessions,
            List<?> fileSystems,
            List<?> terminals,
            List<?> browsers,
            List<?> displays,
            List<?> ports,
            List<?> artifacts,
            String selectedProviderId) {
        this(providers, sessions, fileSystems, terminals, browsers, displays, ports, artifacts, List.of(), selectedProviderId);
    }

    public SandboxRegistry(
            List<?> providers,
            List<?> sessions,
            List<?> fileSystems,
            List<?> terminals,
            List<?> browsers,
            List<?> displays,
            List<?> ports,
            List<?> artifacts,
            List<?> computers,
            String selectedProviderId) {
        this.providers = indexByProviderId(providers, "sandbox providers");
        this.sessions = indexByProviderId(sessions, "sandbox session services");
        this.fileSystems = indexByProviderId(fileSystems, "sandbox file systems");
        this.terminals = indexByProviderId(terminals, "sandbox terminals");
        this.browsers = indexByProviderId(browsers, "sandbox browsers");
        this.displays = indexByProviderId(displays, "sandbox displays");
        this.ports = indexByProviderId(ports, "sandbox ports");
        this.artifacts = indexByProviderId(artifacts, "sandbox artifacts");
        this.computers = indexByProviderId(computers, "sandbox computers");
        this.capabilities = buildCapabilities();
        this.selectedProviderId = resolveSelectedProviderId(selectedProviderId);
    }

    public <T> Map<String, T> providers() {
        return castMap(providers);
    }

    public <T> Map<String, T> getProviders() {
        return providers();
    }

    public <T> Map<String, T> providers(Class<T> type) {
        return typedMap(providers, type);
    }

    public <T> Map<String, T> sessions() {
        return castMap(sessions);
    }

    public <T> Map<String, T> getSessions() {
        return sessions();
    }

    public <T> Map<String, T> sessions(Class<T> type) {
        return typedMap(sessions, type);
    }

    public <T> Map<String, T> fileSystems() {
        return castMap(fileSystems);
    }

    public <T> Map<String, T> getFileSystems() {
        return fileSystems();
    }

    public <T> Map<String, T> fileSystems(Class<T> type) {
        return typedMap(fileSystems, type);
    }

    public <T> Map<String, T> terminals() {
        return castMap(terminals);
    }

    public <T> Map<String, T> getTerminals() {
        return terminals();
    }

    public <T> Map<String, T> terminals(Class<T> type) {
        return typedMap(terminals, type);
    }

    public <T> Map<String, T> browsers() {
        return castMap(browsers);
    }

    public <T> Map<String, T> getBrowsers() {
        return browsers();
    }

    public <T> Map<String, T> browsers(Class<T> type) {
        return typedMap(browsers, type);
    }

    public <T> Map<String, T> displays() {
        return castMap(displays);
    }

    public <T> Map<String, T> getDisplays() {
        return displays();
    }

    public <T> Map<String, T> displays(Class<T> type) {
        return typedMap(displays, type);
    }

    public <T> Map<String, T> ports() {
        return castMap(ports);
    }

    public <T> Map<String, T> getPorts() {
        return ports();
    }

    public <T> Map<String, T> ports(Class<T> type) {
        return typedMap(ports, type);
    }

    public <T> Map<String, T> artifacts() {
        return castMap(artifacts);
    }

    public <T> Map<String, T> getArtifacts() {
        return artifacts();
    }

    public <T> Map<String, T> artifacts(Class<T> type) {
        return typedMap(artifacts, type);
    }

    public <T> Map<String, T> computers() {
        return castMap(computers);
    }

    public <T> Map<String, T> getComputers() {
        return computers();
    }

    public <T> Map<String, T> computers(Class<T> type) {
        return typedMap(computers, type);
    }

    public Set<String> providerIds() {
        return capabilities.keySet();
    }

    public Set<String> getProviderIds() {
        return providerIds();
    }

    public Map<String, ProviderCapabilities> capabilities() {
        return capabilities;
    }

    public Map<String, ProviderCapabilities> getCapabilities() {
        return capabilities();
    }

    public Optional<String> selectedProviderId() {
        return Optional.ofNullable(selectedProviderId);
    }

    public Optional<String> getSelectedProviderId() {
        return selectedProviderId();
    }

    public <T> Optional<T> selectedProvider() {
        return selectedProviderId().map(providers::get).map(SandboxRegistry::cast);
    }

    public <T> Optional<T> getSelectedProvider() {
        return selectedProvider();
    }

    public <T> Optional<T> selectedProvider(Class<T> type) {
        Objects.requireNonNull(type, "type is required");
        return selectedProvider().filter(type::isInstance).map(type::cast);
    }

    public <T> T requireSelectedProvider() {
        return this.<T>selectedProvider()
                .orElseThrow(() -> new IllegalStateException("No sandbox provider is registered"));
    }

    public Optional<ProviderCapabilities> selectedCapabilities() {
        return selectedProviderId().map(capabilities::get);
    }

    public Optional<ProviderCapabilities> getSelectedCapabilities() {
        return selectedCapabilities();
    }

    public ProviderCapabilities requireSelectedCapabilities() {
        return selectedCapabilities()
                .orElseThrow(() -> new IllegalStateException("No sandbox provider capabilities are registered"));
    }

    public Optional<ProviderCapabilities> resolve(String providerId) {
        return Optional.ofNullable(providerId)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(capabilities::get);
    }

    public ProviderCapabilities require(String providerId) {
        return resolve(providerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No sandbox provider registered with id: " + providerId));
    }

    public <T> Optional<T> resolve(String providerId, SandboxCapabilityType capabilityType) {
        return resolve(providerId)
                .flatMap(capabilities -> capabilities.capability(capabilityType));
    }

    public <T> Optional<T> resolve(String providerId, SandboxCapabilityType capabilityType, Class<T> type) {
        Objects.requireNonNull(type, "type is required");
        return resolve(providerId, capabilityType)
                .filter(type::isInstance)
                .map(type::cast);
    }

    public <T> T require(String providerId, SandboxCapabilityType capabilityType) {
        return this.<T>resolve(providerId, capabilityType)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No sandbox " + capabilityType + " capability registered for providerId: " + providerId));
    }

    public <T> T require(String providerId, SandboxCapabilityType capabilityType, Class<T> type) {
        return resolve(providerId, capabilityType, type)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No sandbox " + capabilityType + " capability of type " + type.getName()
                                + " registered for providerId: " + providerId));
    }

    public boolean supports(String providerId, SandboxCapabilityType capabilityType) {
        return resolve(providerId)
                .map(capabilities -> capabilities.supports(capabilityType))
                .orElse(false);
    }

    public <T> Optional<T> selectedCapability(SandboxCapabilityType capabilityType) {
        return selectedCapabilities()
                .flatMap(capabilities -> capabilities.capability(capabilityType));
    }

    public <T> Optional<T> selectedCapability(SandboxCapabilityType capabilityType, Class<T> type) {
        Objects.requireNonNull(type, "type is required");
        return selectedCapability(capabilityType)
                .filter(type::isInstance)
                .map(type::cast);
    }

    public <T> T requireSelectedCapability(SandboxCapabilityType capabilityType) {
        return this.<T>selectedCapability(capabilityType)
                .orElseThrow(() -> new IllegalStateException(
                        "No selected sandbox " + capabilityType + " capability is registered"));
    }

    private Map<String, ProviderCapabilities> buildCapabilities() {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(providers.keySet());
        ids.addAll(sessions.keySet());
        ids.addAll(fileSystems.keySet());
        ids.addAll(terminals.keySet());
        ids.addAll(browsers.keySet());
        ids.addAll(displays.keySet());
        ids.addAll(ports.keySet());
        ids.addAll(artifacts.keySet());
        ids.addAll(computers.keySet());

        Map<String, ProviderCapabilities> grouped = new LinkedHashMap<>();
        for (String providerId : ids) {
            Object provider = providers.get(providerId);
            grouped.put(providerId, new ProviderCapabilities(
                    providerId,
                    provider,
                    firstPresent(sessions.get(providerId), capabilityFromProvider(provider, "session")),
                    firstPresent(fileSystems.get(providerId), capabilityFromProvider(provider, "fileSystem")),
                    firstPresent(terminals.get(providerId), capabilityFromProvider(provider, "terminal")),
                    firstPresent(browsers.get(providerId), capabilityFromProvider(provider, "browser")),
                    firstPresent(displays.get(providerId), capabilityFromProvider(provider, "display")),
                    firstPresent(ports.get(providerId), capabilityFromProvider(provider, "ports")),
                    firstPresent(artifacts.get(providerId), capabilityFromProvider(provider, "artifacts")),
                    firstPresent(computers.get(providerId), capabilityFromProvider(provider, "computer"))));
        }
        return Collections.unmodifiableMap(grouped);
    }

    private static Object firstPresent(Object explicitCapability, Object providerCapability) {
        return explicitCapability == null ? providerCapability : explicitCapability;
    }

    private static Object capabilityFromProvider(Object provider, String capabilityMethod) {
        if (!(provider instanceof SandboxProvider sandboxProvider)) {
            return null;
        }
        return switch (capabilityMethod) {
            case "session" -> sandboxProvider.sessions();
            case "fileSystem" -> sandboxProvider.fileSystem().orElse(null);
            case "terminal" -> sandboxProvider.terminal().orElse(null);
            case "browser" -> sandboxProvider.browser().orElse(null);
            case "display" -> sandboxProvider.display().orElse(null);
            case "ports" -> sandboxProvider.ports().orElse(null);
            case "artifacts" -> sandboxProvider.artifacts().orElse(null);
            case "computer" -> sandboxProvider.computer().orElse(null);
            default -> null;
        };
    }

    private String resolveSelectedProviderId(String requestedProviderId) {
        if (hasText(requestedProviderId)) {
            String providerId = requestedProviderId.trim();
            if (!capabilities.containsKey(providerId)) {
                throw new IllegalArgumentException("Configured sandbox provider is not registered: " + providerId);
            }
            return providerId;
        }
        if (providers.size() == 1) {
            return providers.keySet().iterator().next();
        }
        if (capabilities.size() == 1) {
            return capabilities.keySet().iterator().next();
        }
        return null;
    }

    private static Map<String, Object> indexByProviderId(List<?> values, String capabilityName) {
        return indexByProviderId(values, SandboxRegistry::providerId, capabilityName);
    }

    private static <T> Map<String, T> indexByProviderId(
            List<? extends T> values,
            Function<T, String> providerIdExtractor,
            String capabilityName) {
        Objects.requireNonNull(providerIdExtractor, "providerIdExtractor is required");
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T value : emptyIfNull(values)) {
            if (value == null) {
                continue;
            }
            String providerId = providerIdExtractor.apply(value);
            if (!hasText(providerId)) {
                throw new IllegalArgumentException(capabilityName + " must declare a providerId");
            }
            String normalizedProviderId = providerId.trim();
            T previous = indexed.putIfAbsent(normalizedProviderId, value);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate " + capabilityName + " registered for providerId: " + normalizedProviderId);
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static <T> List<? extends T> emptyIfNull(List<? extends T> values) {
        return values == null ? List.of() : values;
    }

    private static String providerId(Object value) {
        Method method = providerIdMethod(value);
        try {
            Object result = method.invoke(value);
            return result == null ? null : result.toString();
        } catch (IllegalAccessException ex) {
            throw new IllegalArgumentException(
                    "providerId method is not accessible on " + value.getClass().getName(),
                    ex);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalArgumentException("providerId method failed on " + value.getClass().getName(), cause);
        }
    }

    private static Method providerIdMethod(Object value) {
        Objects.requireNonNull(value, "value is required");
        for (String methodName : List.of("providerId", "getProviderId")) {
            try {
                Method method = value.getClass().getMethod(methodName);
                if (method.getParameterCount() == 0) {
                    return method;
                }
            } catch (NoSuchMethodException ex) {
                log.debug("providerIdMethod fallback: {}", ex.toString());
                // Try the JavaBean-style name below.
            }
        }
        throw new IllegalArgumentException(value.getClass().getName() + " must expose providerId()");
    }

    private static <T> Map<String, T> typedMap(Map<String, Object> values, Class<T> type) {
        Objects.requireNonNull(type, "type is required");
        Map<String, T> typed = new LinkedHashMap<>();
        values.forEach((providerId, value) -> {
            if (type.isInstance(value)) {
                typed.put(providerId, type.cast(value));
            }
        });
        return Collections.unmodifiableMap(typed);
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> castMap(Map<String, Object> values) {
        return (Map<String, T>) (Map<?, ?>) values;
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static final class ProviderCapabilities {

        private final String providerId;
        private final Object provider;
        private final Object session;
        private final Object fileSystem;
        private final Object terminal;
        private final Object browser;
        private final Object display;
        private final Object ports;
        private final Object artifacts;
        private final Object computer;

        private ProviderCapabilities(
                String providerId,
                Object provider,
                Object session,
                Object fileSystem,
                Object terminal,
                Object browser,
                Object display,
                Object ports,
                Object artifacts,
                Object computer) {
            this.providerId = providerId;
            this.provider = provider;
            this.session = session;
            this.fileSystem = fileSystem;
            this.terminal = terminal;
            this.browser = browser;
            this.display = display;
            this.ports = ports;
            this.artifacts = artifacts;
            this.computer = computer;
        }

        public String providerId() {
            return providerId;
        }

        public String getProviderId() {
            return providerId();
        }

        public <T> Optional<T> provider() {
            return Optional.ofNullable(provider).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getProvider() {
            return provider();
        }

        public <T> Optional<T> provider(Class<T> type) {
            return typedValue(provider, type);
        }

        public <T> Optional<T> session() {
            return Optional.ofNullable(session).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getSession() {
            return session();
        }

        public <T> Optional<T> session(Class<T> type) {
            return typedValue(session, type);
        }

        public <T> Optional<T> fileSystem() {
            return Optional.ofNullable(fileSystem).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getFileSystem() {
            return fileSystem();
        }

        public <T> Optional<T> fileSystem(Class<T> type) {
            return typedValue(fileSystem, type);
        }

        public <T> Optional<T> terminal() {
            return Optional.ofNullable(terminal).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getTerminal() {
            return terminal();
        }

        public <T> Optional<T> terminal(Class<T> type) {
            return typedValue(terminal, type);
        }

        public <T> Optional<T> browser() {
            return Optional.ofNullable(browser).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getBrowser() {
            return browser();
        }

        public <T> Optional<T> browser(Class<T> type) {
            return typedValue(browser, type);
        }

        public <T> Optional<T> display() {
            return Optional.ofNullable(display).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getDisplay() {
            return display();
        }

        public <T> Optional<T> display(Class<T> type) {
            return typedValue(display, type);
        }

        public <T> Optional<T> ports() {
            return Optional.ofNullable(ports).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getPorts() {
            return ports();
        }

        public <T> Optional<T> ports(Class<T> type) {
            return typedValue(ports, type);
        }

        public <T> Optional<T> artifacts() {
            return Optional.ofNullable(artifacts).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getArtifacts() {
            return artifacts();
        }

        public <T> Optional<T> artifacts(Class<T> type) {
            return typedValue(artifacts, type);
        }

        public <T> Optional<T> computer() {
            return Optional.ofNullable(computer).map(SandboxRegistry::cast);
        }

        public <T> Optional<T> getComputer() {
            return computer();
        }

        public <T> Optional<T> computer(Class<T> type) {
            return typedValue(computer, type);
        }

        public <T> Optional<T> capability(SandboxCapabilityType capabilityType) {
            if (capabilityType == null) {
                return Optional.empty();
            }
            return switch (capabilityType) {
                case SESSION -> session();
                case FILE_SYSTEM -> fileSystem();
                case TERMINAL -> terminal();
                case BROWSER -> browser();
                case DISPLAY -> display();
                case PORTS -> ports();
                case ARTIFACTS -> artifacts();
                case COMPUTER -> computer();
            };
        }

        public <T> Optional<T> capability(SandboxCapabilityType capabilityType, Class<T> type) {
            Objects.requireNonNull(type, "type is required");
            return capability(capabilityType)
                    .filter(type::isInstance)
                    .map(type::cast);
        }

        public boolean supports(SandboxCapabilityType capabilityType) {
            return capability(capabilityType).isPresent();
        }

        public Set<SandboxCapabilityType> capabilityTypes() {
            EnumSet<SandboxCapabilityType> types = EnumSet.noneOf(SandboxCapabilityType.class);
            if (session != null) {
                types.add(SandboxCapabilityType.SESSION);
            }
            if (fileSystem != null) {
                types.add(SandboxCapabilityType.FILE_SYSTEM);
            }
            if (terminal != null) {
                types.add(SandboxCapabilityType.TERMINAL);
            }
            if (browser != null) {
                types.add(SandboxCapabilityType.BROWSER);
            }
            if (display != null) {
                types.add(SandboxCapabilityType.DISPLAY);
            }
            if (ports != null) {
                types.add(SandboxCapabilityType.PORTS);
            }
            if (artifacts != null) {
                types.add(SandboxCapabilityType.ARTIFACTS);
            }
            if (computer != null) {
                types.add(SandboxCapabilityType.COMPUTER);
            }
            return Set.copyOf(types);
        }

        public Set<SandboxCapabilityType> getCapabilityTypes() {
            return capabilityTypes();
        }

        public List<Object> serviceCapabilities() {
            List<Object> registered = new ArrayList<>();
            Optional.ofNullable(session).ifPresent(registered::add);
            Optional.ofNullable(fileSystem).ifPresent(registered::add);
            Optional.ofNullable(terminal).ifPresent(registered::add);
            Optional.ofNullable(browser).ifPresent(registered::add);
            Optional.ofNullable(display).ifPresent(registered::add);
            Optional.ofNullable(ports).ifPresent(registered::add);
            Optional.ofNullable(artifacts).ifPresent(registered::add);
            Optional.ofNullable(computer).ifPresent(registered::add);
            return List.copyOf(registered);
        }

        public List<Object> getServiceCapabilities() {
            return serviceCapabilities();
        }

        public List<Object> registeredCapabilities() {
            List<Object> registered = new ArrayList<>();
            Optional.ofNullable(provider).ifPresent(registered::add);
            if (provider == null) {
                Optional.ofNullable(session).ifPresent(registered::add);
            }
            Optional.ofNullable(fileSystem).ifPresent(registered::add);
            Optional.ofNullable(terminal).ifPresent(registered::add);
            Optional.ofNullable(browser).ifPresent(registered::add);
            Optional.ofNullable(display).ifPresent(registered::add);
            Optional.ofNullable(ports).ifPresent(registered::add);
            Optional.ofNullable(artifacts).ifPresent(registered::add);
            Optional.ofNullable(computer).ifPresent(registered::add);
            return List.copyOf(registered);
        }

        public List<Object> getRegisteredCapabilities() {
            return registeredCapabilities();
        }

        private static <T> Optional<T> typedValue(Object value, Class<T> type) {
            Objects.requireNonNull(type, "type is required");
            return Optional.ofNullable(value).filter(type::isInstance).map(type::cast);
        }
    }
}
