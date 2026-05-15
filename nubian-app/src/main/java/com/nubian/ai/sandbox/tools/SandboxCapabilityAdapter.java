package com.nubian.ai.sandbox.tools;

import com.nubian.ai.runtime.tool.ToolExecutionContext;
import com.nubian.ai.sandbox.api.SandboxArtifacts;
import com.nubian.ai.sandbox.api.SandboxBrowser;
import com.nubian.ai.sandbox.api.SandboxComputer;
import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.api.SandboxPorts;
import com.nubian.ai.sandbox.api.SandboxProvider;
import com.nubian.ai.sandbox.api.SandboxSessionService;
import com.nubian.ai.sandbox.api.SandboxTerminal;
import com.nubian.ai.sandbox.model.SandboxArtifact;
import com.nubian.ai.sandbox.model.SandboxBrowserAction;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxFile;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reflection adapter for the provider-neutral sandbox API.
 *
 * <p>The concrete {@code com.nubian.ai.sandbox.api.*} interfaces are optional
 * for this module at compile time. This adapter intentionally discovers the
 * common provider/capability shapes by method name so the bridge can be loaded
 * safely before those API types are present.</p>
 */
final class SandboxCapabilityAdapter {
    private static final Logger logger = LoggerFactory.getLogger(SandboxCapabilityAdapter.class);

    private static final Set<String> PROVIDER_METHODS = Set.of(
            "capabilities",
            "getCapabilities",
            "sandboxCapabilities",
            "getSandboxCapabilities",
            "tools",
            "getTools",
            "operations",
            "getOperations");

    private static final List<String> NAME_METHODS = List.of(
            "toolName",
            "getToolName",
            "functionName",
            "getFunctionName",
            "name",
            "getName",
            "id",
            "getId");

    private static final List<String> DESCRIPTION_METHODS = List.of(
            "description",
            "getDescription",
            "summary",
            "getSummary");

    private static final List<String> SCHEMA_METHODS = List.of(
            "inputSchema",
            "getInputSchema",
            "parameterSchema",
            "getParameterSchema",
            "parameters",
            "getParameters",
            "schema",
            "getSchema",
            "openApiSchema",
            "getOpenApiSchema");

    private static final List<String> EXECUTION_METHODS = List.of(
            "execute",
            "executeAsync",
            "run",
            "invoke",
            "call",
            "apply",
            "handle");

    private final Object delegate;
    private final String name;
    private final String originalName;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Method executionMethod;

    private SandboxCapabilityAdapter(
            Object delegate,
            String name,
            String originalName,
            String description,
            Map<String, Object> inputSchema,
            Method executionMethod) {
        this.delegate = delegate;
        this.name = name;
        this.originalName = originalName;
        this.description = description;
        this.inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        this.executionMethod = executionMethod;
    }

    static List<SandboxCapabilityAdapter> adaptAll(Object providerOrCapability) {
        if (providerOrCapability == null) {
            return List.of();
        }
        if (providerOrCapability instanceof SandboxCapabilityAdapter adapter) {
            return List.of(adapter);
        }
        List<SandboxCapabilityAdapter> coreAdapters = adaptCoreApi(providerOrCapability);
        if (!coreAdapters.isEmpty()) {
            return coreAdapters;
        }

        List<SandboxCapabilityAdapter> adapters = new ArrayList<>();
        for (Method method : providerMethods(providerOrCapability.getClass())) {
            try {
                method.setAccessible(true);
                Object value = method.invoke(providerOrCapability);
                adapters.addAll(adaptProviderValue(value));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                logger.warn(
                        "Failed to inspect sandbox capability provider {}.{}: {}",
                        providerOrCapability.getClass().getName(),
                        method.getName(),
                        ex.getMessage());
            }
        }

        if (adapters.isEmpty()) {
            create(null, providerOrCapability).ifPresent(adapters::add);
        }
        return adapters;
    }

    private static List<SandboxCapabilityAdapter> adaptCoreApi(Object providerOrCapability) {
        if (providerOrCapability instanceof SandboxProvider provider) {
            List<SandboxCapabilityAdapter> adapters = new ArrayList<>();
            adapters.add(sessionAdapter(provider.providerId(), provider.sessions()));
            provider.fileSystem().ifPresent(fileSystem -> adapters.add(fileSystemAdapter(provider.providerId(), fileSystem)));
            provider.terminal().ifPresent(terminal -> adapters.add(terminalAdapter(provider.providerId(), terminal)));
            provider.browser().ifPresent(browser -> adapters.add(browserAdapter(provider.providerId(), browser)));
            provider.display().ifPresent(display -> adapters.add(displayAdapter(provider.providerId(), display)));
            provider.ports().ifPresent(ports -> adapters.add(portsAdapter(provider.providerId(), ports)));
            provider.artifacts().ifPresent(artifacts -> adapters.add(artifactsAdapter(provider.providerId(), artifacts)));
            provider.computer().ifPresent(computer -> adapters.add(computerAdapter(provider.providerId(), computer)));
            return adapters;
        }
        if (providerOrCapability instanceof SandboxSessionService sessions) {
            return List.of(sessionAdapter(sessions.providerId(), sessions));
        }
        if (providerOrCapability instanceof SandboxFileSystem fileSystem) {
            return List.of(fileSystemAdapter(fileSystem.providerId(), fileSystem));
        }
        if (providerOrCapability instanceof SandboxTerminal terminal) {
            return List.of(terminalAdapter(terminal.providerId(), terminal));
        }
        if (providerOrCapability instanceof SandboxBrowser browser) {
            return List.of(browserAdapter(browser.providerId(), browser));
        }
        if (providerOrCapability instanceof SandboxDisplay display) {
            return List.of(displayAdapter(display.providerId(), display));
        }
        if (providerOrCapability instanceof SandboxPorts ports) {
            return List.of(portsAdapter(ports.providerId(), ports));
        }
        if (providerOrCapability instanceof SandboxArtifacts artifacts) {
            return List.of(artifactsAdapter(artifacts.providerId(), artifacts));
        }
        if (providerOrCapability instanceof SandboxComputer computer) {
            return List.of(computerAdapter(computer.providerId(), computer));
        }
        return List.of();
    }

    private static SandboxCapabilityAdapter sessionAdapter(String providerId, SandboxSessionService sessions) {
        return operationAdapter(
                providerId,
                "session",
                "Manage sandbox sessions for provider " + providerId,
                sessionSchema(),
                (input, context) -> switch (action(input, "create")) {
                    case "create" -> sessions.createSession(stringMap(input.get("labels")), stringMap(input.get("metadata")));
                    case "get" -> sessions.getSession(text(input, "sessionId"));
                    case "list" -> sessions.listSessions(stringMap(input.get("labels")));
                    case "start" -> sessions.startSession(text(input, "sessionId"));
                    case "stop" -> sessions.stopSession(text(input, "sessionId"));
                    case "delete" -> sessions.deleteSession(text(input, "sessionId"));
                    default -> throw new IllegalArgumentException("Unsupported session action: " + input.get("action"));
                });
    }

    private static SandboxCapabilityAdapter fileSystemAdapter(String providerId, SandboxFileSystem fileSystem) {
        return operationAdapter(
                providerId,
                "files",
                "Read and write sandbox files for provider " + providerId,
                fileSystemSchema(),
                (input, context) -> {
                    String sessionId = text(input, "sessionId");
                    String path = text(input, "path");
                    byte[] content = fileContent(input);
                    return switch (action(input, "list")) {
                        case "read" -> fileSystem.readFile(sessionId, path);
                        case "write" -> fileSystem.writeFile(sessionId, new SandboxFile(
                                path,
                                false,
                                content.length,
                                Instant.now(),
                                optionalText(input, "mediaType", "text/plain"),
                                content,
                                stringMap(input.get("metadata"))));
                        case "list" -> fileSystem.listFiles(sessionId, path == null || path.isBlank() ? "" : path);
                        case "mkdir" -> fileSystem.createDirectory(sessionId, path);
                        case "delete" -> fileSystem.deletePath(sessionId, path);
                        default -> throw new IllegalArgumentException("Unsupported file action: " + input.get("action"));
                    };
                });
    }

    private static SandboxCapabilityAdapter terminalAdapter(String providerId, SandboxTerminal terminal) {
        return operationAdapter(
                providerId,
                "terminal",
                "Execute sandbox terminal commands for provider " + providerId,
                terminalSchema(),
                (input, context) -> {
                    String sessionId = text(input, "sessionId");
                    if ("interrupt".equals(action(input, "execute"))) {
                        return terminal.interrupt(sessionId, text(input, "commandId"));
                    }
                    return terminal.execute(sessionId, new SandboxCommand(
                            text(input, "command"),
                            stringList(input.get("arguments")),
                            optionalText(input, "workingDirectory", ""),
                            stringMap(input.get("environment")),
                            duration(input),
                            bool(input, "interactive", false),
                            stringMap(input.get("metadata"))));
                });
    }

    private static SandboxCapabilityAdapter browserAdapter(String providerId, SandboxBrowser browser) {
        return operationAdapter(
                providerId,
                "browser",
                "Control or observe sandbox browser state for provider " + providerId,
                browserSchema(),
                (input, context) -> {
                    String sessionId = text(input, "sessionId");
                    String action = action(input, "observe");
                    if ("observe".equals(action)) {
                        return browser.observe(sessionId);
                    }
                    SandboxBrowserAction.Type type = SandboxBrowserAction.Type.valueOf(action.toUpperCase(Locale.ROOT));
                    return browser.performAction(sessionId, new SandboxBrowserAction(
                            type,
                            stringMap(input.get("parameters")),
                            duration(input),
                            stringMap(input.get("metadata"))));
                });
    }

    private static SandboxCapabilityAdapter displayAdapter(String providerId, SandboxDisplay display) {
        return operationAdapter(
                providerId,
                "display",
                "Capture or resize sandbox display for provider " + providerId,
                displaySchema(),
                (input, context) -> {
                    String sessionId = text(input, "sessionId");
                    if ("resize".equals(action(input, "capture"))) {
                        return display.resizeDisplay(sessionId, integer(input, "width", 1920), integer(input, "height", 1200));
                    }
                    return display.captureFrame(sessionId);
                });
    }

    private static SandboxCapabilityAdapter portsAdapter(String providerId, SandboxPorts ports) {
        return operationAdapter(
                providerId,
                "ports",
                "Expose or inspect sandbox ports for provider " + providerId,
                portsSchema(),
                (input, context) -> {
                    String sessionId = text(input, "sessionId");
                    return switch (action(input, "list")) {
                        case "expose" -> ports.exposePort(
                                sessionId,
                                integer(input, "port", 0),
                                optionalText(input, "protocol", "http"),
                                bool(input, "publicAccess", false));
                        case "list" -> ports.listPorts(sessionId);
                        case "close" -> ports.closePort(sessionId, integer(input, "port", 0));
                        default -> throw new IllegalArgumentException("Unsupported port action: " + input.get("action"));
                    };
                });
    }

    private static SandboxCapabilityAdapter artifactsAdapter(String providerId, SandboxArtifacts artifacts) {
        return operationAdapter(
                providerId,
                "artifacts",
                "Create, list, fetch, or delete sandbox artifacts for provider " + providerId,
                artifactsSchema(),
                (input, context) -> {
                    String sessionId = text(input, "sessionId");
                    return switch (action(input, "list")) {
                        case "create" -> artifacts.createArtifact(sessionId, new SandboxArtifact(
                                providerId,
                                sessionId,
                                optionalText(input, "artifactId", optionalText(input, "path", "")),
                                optionalText(input, "name", optionalText(input, "path", "artifact")),
                                optionalText(input, "path", ""),
                                optionalText(input, "mediaType", "application/octet-stream"),
                                longValue(input, "sizeBytes", 0L),
                                uri(input.get("uri")),
                                Instant.now(),
                                stringMap(input.get("metadata"))));
                        case "get" -> artifacts.getArtifact(sessionId, text(input, "artifactId"));
                        case "list" -> artifacts.listArtifacts(sessionId);
                        case "delete" -> artifacts.deleteArtifact(sessionId, text(input, "artifactId"));
                        default -> throw new IllegalArgumentException("Unsupported artifact action: " + input.get("action"));
                    };
                });
    }

    private static SandboxCapabilityAdapter computerAdapter(String providerId, SandboxComputer computer) {
        return operationAdapter(
                providerId,
                "computer",
                "Inspect full computer sandbox environment for provider " + providerId,
                computerSchema(),
                (input, context) -> computer.inspect(text(input, "sessionId")));
    }

    private static SandboxCapabilityAdapter operationAdapter(
            String providerId,
            String capabilityName,
            String description,
            Map<String, Object> inputSchema,
            BiFunction<Map<String, Object>, ToolExecutionContext, Object> invoker) {
        String originalName = providerId + "." + capabilityName;
        return new SandboxCapabilityAdapter(
                invoker,
                normalizeName(providerId + "_" + capabilityName),
                originalName,
                description,
                inputSchema,
                null);
    }

    private static Collection<Method> providerMethods(Class<?> type) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method method : allMethods(type)) {
            if (method.getParameterCount() == 0
                    && !Modifier.isStatic(method.getModifiers())
                    && PROVIDER_METHODS.contains(method.getName())) {
                methods.putIfAbsent(method.getName(), method);
            }
        }
        return methods.values();
    }

    private static List<SandboxCapabilityAdapter> adaptProviderValue(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(SandboxCapabilityAdapter::adaptProviderValue).orElseGet(List::of);
        }
        if (value instanceof Map<?, ?> map) {
            List<SandboxCapabilityAdapter> adapters = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String explicitName = entry.getKey() == null ? null : entry.getKey().toString();
                create(explicitName, entry.getValue()).ifPresent(adapters::add);
            }
            return adapters;
        }
        if (value instanceof Stream<?> stream) {
            return stream.flatMap(item -> adaptProviderValue(item).stream()).toList();
        }
        if (value instanceof Iterable<?> iterable) {
            List<SandboxCapabilityAdapter> adapters = new ArrayList<>();
            for (Object item : iterable) {
                adapters.addAll(adaptProviderValue(item));
            }
            return adapters;
        }
        if (value.getClass().isArray()) {
            List<SandboxCapabilityAdapter> adapters = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                adapters.addAll(adaptProviderValue(Array.get(value, i)));
            }
            return adapters;
        }
        return create(null, value).map(List::of).orElseGet(List::of);
    }

    private static Optional<SandboxCapabilityAdapter> create(String explicitName, Object capability) {
        if (capability == null) {
            return Optional.empty();
        }
        if (capability instanceof SandboxCapabilityAdapter adapter) {
            return Optional.of(adapter);
        }

        Method executionMethod = null;
        if (!(capability instanceof Function<?, ?>)
                && !(capability instanceof BiFunction<?, ?, ?>)
                && !(capability instanceof Supplier<?>)) {
            executionMethod = selectExecutionMethod(capability.getClass());
            if (executionMethod == null) {
                return Optional.empty();
            }
        }

        String originalName = firstText(explicitName, readString(capability, NAME_METHODS));
        if (originalName == null) {
            originalName = defaultName(capability.getClass());
        }

        String normalizedName = normalizeName(originalName);
        String description = firstText(
                readString(capability, DESCRIPTION_METHODS),
                "Execute sandbox capability " + originalName);

        return Optional.of(new SandboxCapabilityAdapter(
                capability,
                normalizedName,
                originalName,
                description,
                readMap(capability, SCHEMA_METHODS),
                executionMethod));
    }

    private static Method selectExecutionMethod(Class<?> type) {
        return allMethods(type).stream()
                .filter(method -> EXECUTION_METHODS.contains(method.getName()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> !method.isBridge())
                .filter(method -> !method.isSynthetic())
                .filter(SandboxCapabilityAdapter::hasSupportedParameters)
                .min(Comparator.comparingInt(SandboxCapabilityAdapter::executionScore))
                .orElse(null);
    }

    private static boolean hasSupportedParameters(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            return true;
        }
        if (parameterTypes.length == 1) {
            return isSupportedSingleParameter(parameterTypes[0]);
        }
        if (parameterTypes.length == 2) {
            return isMap(parameterTypes[0]) && ToolExecutionContext.class.isAssignableFrom(parameterTypes[1])
                    || ToolExecutionContext.class.isAssignableFrom(parameterTypes[0]) && isMap(parameterTypes[1]);
        }
        return false;
    }

    private static boolean isSupportedSingleParameter(Class<?> parameterType) {
        return isMap(parameterType)
                || ToolExecutionContext.class.isAssignableFrom(parameterType)
                || String.class.isAssignableFrom(parameterType)
                || Object.class.equals(parameterType)
                || parameterType.isPrimitive()
                || Number.class.isAssignableFrom(parameterType)
                || Boolean.class.equals(parameterType);
    }

    private static int executionScore(Method method) {
        int nameScore = EXECUTION_METHODS.indexOf(method.getName()) * 100;
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 2 && isMap(parameterTypes[0])) {
            return nameScore;
        }
        if (parameterTypes.length == 2) {
            return nameScore + 1;
        }
        if (parameterTypes.length == 1 && isMap(parameterTypes[0])) {
            return nameScore + 5;
        }
        if (parameterTypes.length == 1 && ToolExecutionContext.class.isAssignableFrom(parameterTypes[0])) {
            return nameScore + 10;
        }
        if (parameterTypes.length == 1) {
            return nameScore + 20;
        }
        return nameScore + 30;
    }

    private static Map<String, Object> sessionSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumProperty(
                "Session operation to execute.",
                "create", "get", "list", "start", "stop", "delete"));
        properties.put("sessionId", stringProperty("Provider-owned sandbox session id."));
        properties.put("labels", stringMapProperty("Labels to attach to or filter sessions by."));
        properties.put("metadata", stringMapProperty("Provider-neutral session metadata."));
        return objectSchema(properties, List.of("action"));
    }

    private static Map<String, Object> fileSystemSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumProperty(
                "File operation to execute.",
                "read", "write", "list", "mkdir", "delete"));
        properties.put("sessionId", stringProperty("Provider-owned sandbox session id."));
        properties.put("path", stringProperty("Sandbox-relative or provider-supported absolute path."));
        properties.put("content", stringProperty("Text content for write operations."));
        Map<String, Object> base64 = stringProperty("Base64-encoded file content for write operations.");
        base64.put("contentEncoding", "base64");
        properties.put("contentBase64", base64);
        properties.put("mediaType", stringProperty("IANA media type for written content."));
        properties.put("metadata", stringMapProperty("Provider-neutral file metadata."));
        return objectSchema(properties, List.of("action", "sessionId", "path"));
    }

    private static Map<String, Object> terminalSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumProperty("Terminal operation to execute.", "execute", "interrupt"));
        properties.put("sessionId", stringProperty("Provider-owned sandbox session id."));
        properties.put("command", stringProperty("Executable or shell command name."));
        properties.put("arguments", stringArrayProperty("Command arguments."));
        properties.put("workingDirectory", stringProperty("Working directory inside the sandbox."));
        properties.put("environment", stringMapProperty("Environment variables for the command."));
        properties.put("timeoutSeconds", integerProperty("Command timeout in seconds."));
        properties.put("timeoutMillis", integerProperty("Command timeout in milliseconds."));
        properties.put("interactive", booleanProperty("Whether the command expects interactive terminal behavior."));
        properties.put("commandId", stringProperty("Provider command id for interrupt operations."));
        properties.put("metadata", stringMapProperty("Provider-neutral command metadata."));
        return objectSchema(properties, List.of("action", "sessionId"));
    }

    private static Map<String, Object> browserSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumProperty(
                "Browser operation to execute.",
                "observe", "navigate", "click", "type", "press_key", "scroll",
                "back", "forward", "reload", "wait", "evaluate", "screenshot"));
        properties.put("sessionId", stringProperty("Provider-owned sandbox session id."));
        properties.put("parameters", stringMapProperty("Action-specific browser parameters."));
        properties.put("timeoutSeconds", integerProperty("Browser action timeout in seconds."));
        properties.put("timeoutMillis", integerProperty("Browser action timeout in milliseconds."));
        properties.put("metadata", stringMapProperty("Provider-neutral browser metadata."));
        return objectSchema(properties, List.of("action", "sessionId"));
    }

    private static Map<String, Object> displaySchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumProperty("Display operation to execute.", "capture", "resize"));
        properties.put("sessionId", stringProperty("Provider-owned sandbox session id."));
        properties.put("width", integerProperty("Display width in pixels for resize operations."));
        properties.put("height", integerProperty("Display height in pixels for resize operations."));
        return objectSchema(properties, List.of("action", "sessionId"));
    }

    private static Map<String, Object> portsSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumProperty("Port operation to execute.", "expose", "list", "close"));
        properties.put("sessionId", stringProperty("Provider-owned sandbox session id."));
        properties.put("port", integerProperty("Container or sandbox port number."));
        properties.put("protocol", stringProperty("Port protocol such as http, tcp, or udp."));
        properties.put("publicAccess", booleanProperty("Whether the exposed port may be publicly reachable."));
        return objectSchema(properties, List.of("action", "sessionId"));
    }

    private static Map<String, Object> artifactsSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumProperty("Artifact operation to execute.", "create", "get", "list", "delete"));
        properties.put("sessionId", stringProperty("Provider-owned sandbox session id."));
        properties.put("artifactId", stringProperty("Provider-owned artifact id."));
        properties.put("name", stringProperty("Human-readable artifact name."));
        properties.put("path", stringProperty("Sandbox path backing the artifact."));
        properties.put("mediaType", stringProperty("IANA media type for the artifact."));
        properties.put("sizeBytes", integerProperty("Artifact size in bytes."));
        properties.put("uri", stringProperty("Optional durable URI for the artifact."));
        properties.put("metadata", stringMapProperty("Provider-neutral artifact metadata."));
        return objectSchema(properties, List.of("action", "sessionId"));
    }

    private static Map<String, Object> computerSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", enumProperty("Computer operation to execute.", "inspect"));
        properties.put("sessionId", stringProperty("Provider-owned sandbox session id."));
        return objectSchema(properties, List.of("action", "sessionId"));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", true);
        return schema;
    }

    private static Map<String, Object> enumProperty(String description, String... values) {
        Map<String, Object> property = stringProperty(description);
        property.put("enum", List.of(values));
        return property;
    }

    private static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> integerProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> booleanProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "boolean");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> stringArrayProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "array");
        property.put("description", description);
        property.put("items", Map.of("type", "string"));
        return property;
    }

    private static Map<String, Object> stringMapProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "object");
        property.put("description", description);
        property.put("additionalProperties", Map.of("type", "string"));
        return property;
    }

    private static String action(Map<String, Object> input, String defaultAction) {
        return optionalText(input, "action", defaultAction).trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : value.toString();
    }

    private static String optionalText(Map<String, Object> input, String key, String defaultValue) {
        String value = text(input, key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static boolean bool(Map<String, Object> input, String key, boolean defaultValue) {
        Object value = input.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static int integer(Map<String, Object> input, String key, int defaultValue) {
        Object value = input.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static long longValue(Map<String, Object> input, String key, long defaultValue) {
        Object value = input.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static Duration duration(Map<String, Object> input) {
        Object value = input.get("timeoutSeconds");
        if (value == null) {
            value = input.get("timeoutMillis");
            if (value instanceof Number number) {
                return Duration.ofMillis(number.longValue());
            }
            if (value != null) {
                return Duration.ofMillis(Long.parseLong(value.toString()));
            }
            return null;
        }
        if (value instanceof Number number) {
            return Duration.ofSeconds(number.longValue());
        }
        return Duration.ofSeconds(Long.parseLong(value.toString()));
    }

    private static URI uri(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return URI.create(value.toString());
    }

    private static byte[] fileContent(Map<String, Object> input) {
        Object base64 = input.get("contentBase64");
        if (base64 != null && !base64.toString().isBlank()) {
            return Base64.getDecoder().decode(base64.toString());
        }
        return bytes(input.get("content"));
    }

    private static byte[] bytes(Object value) {
        if (value == null) {
            return new byte[0];
        }
        if (value instanceof byte[] byteArray) {
            return byteArray.clone();
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, mapValue) -> {
            if (key != null && mapValue != null) {
                result.put(key.toString(), mapValue.toString());
            }
        });
        return Map.copyOf(result);
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return List.copyOf(result);
        }
        if (value.getClass().isArray()) {
            List<String> result = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                Object item = Array.get(value, index);
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return List.copyOf(result);
        }
        String text = value.toString();
        return text.isBlank() ? List.of() : List.of(text);
    }

    Object execute(Map<String, Object> input, ToolExecutionContext context) throws Exception {
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        if (delegate instanceof BiFunction<?, ?, ?> biFunction) {
            @SuppressWarnings("unchecked")
            BiFunction<Map<String, Object>, ToolExecutionContext, Object> typed =
                    (BiFunction<Map<String, Object>, ToolExecutionContext, Object>) biFunction;
            return typed.apply(safeInput, context);
        }
        if (delegate instanceof Function<?, ?> function) {
            @SuppressWarnings("unchecked")
            Function<Map<String, Object>, Object> typed = (Function<Map<String, Object>, Object>) function;
            return typed.apply(safeInput);
        }
        if (delegate instanceof Supplier<?> supplier) {
            return supplier.get();
        }

        executionMethod.setAccessible(true);
        return executionMethod.invoke(delegate, invocationArguments(executionMethod, safeInput, context));
    }

    private static Object[] invocationArguments(
            Method method,
            Map<String, Object> input,
            ToolExecutionContext context) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            return new Object[0];
        }
        if (parameterTypes.length == 1) {
            return new Object[] {singleArgument(parameterTypes[0], input, context)};
        }
        Object first = isMap(parameterTypes[0]) ? input : context;
        Object second = isMap(parameterTypes[1]) ? input : context;
        return new Object[] {first, second};
    }

    private static Object singleArgument(
            Class<?> parameterType,
            Map<String, Object> input,
            ToolExecutionContext context) {
        if (isMap(parameterType) || Object.class.equals(parameterType)) {
            return input;
        }
        if (ToolExecutionContext.class.isAssignableFrom(parameterType)) {
            return context;
        }

        Object value = selectScalarInput(input);
        if (String.class.isAssignableFrom(parameterType)) {
            return value == null ? null : value.toString();
        }
        if (parameterType == boolean.class || parameterType == Boolean.class) {
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
        }
        if (parameterType == int.class || parameterType == Integer.class) {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
        }
        if (parameterType == long.class || parameterType == Long.class) {
            return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
        }
        if (parameterType == double.class || parameterType == Double.class) {
            return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
        }
        if (parameterType == float.class || parameterType == Float.class) {
            return value instanceof Number number ? number.floatValue() : Float.parseFloat(String.valueOf(value));
        }
        return value;
    }

    private static Object selectScalarInput(Map<String, Object> input) {
        for (String key : List.of("value", "input", "payload", "command", "path", "content")) {
            if (input.containsKey(key)) {
                return input.get(key);
            }
        }
        return input.size() == 1 ? input.values().iterator().next() : input;
    }

    private static List<Method> allMethods(Class<?> type) {
        Map<String, Method> methods = new LinkedHashMap<>();
        Class<?> current = type;
        while (current != null && !Object.class.equals(current)) {
            for (Method method : current.getDeclaredMethods()) {
                methods.putIfAbsent(signature(method), method);
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            methods.putIfAbsent(signature(method), method);
        }
        return List.copyOf(methods.values());
    }

    private static String signature(Method method) {
        StringBuilder builder = new StringBuilder(method.getName()).append('(');
        for (Class<?> parameterType : method.getParameterTypes()) {
            builder.append(parameterType.getName()).append(',');
        }
        return builder.append(')').toString();
    }

    private static boolean isMap(Class<?> type) {
        return Map.class.isAssignableFrom(type);
    }

    private static String readString(Object target, List<String> methodNames) {
        Object value = readValue(target, methodNames);
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> readMap(Object target, List<String> methodNames) {
        Object value = readValue(target, methodNames);
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            rawMap.forEach((key, mapValue) -> {
                if (key != null) {
                    result.put(key.toString(), mapValue);
                }
            });
            return result;
        }
        return Map.of();
    }

    private static Object readValue(Object target, List<String> methodNames) {
        Map<String, Method> methodsByName = new HashMap<>();
        for (Method method : allMethods(target.getClass())) {
            if (method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())) {
                methodsByName.putIfAbsent(method.getName(), method);
            }
        }
        for (String methodName : methodNames) {
            Method method = methodsByName.get(methodName);
            if (method == null) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(target);
                if (value != null && !value.toString().isBlank()) {
                    return value;
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                logger.debug(
                        "Failed to read sandbox capability metadata {}.{}: {}",
                        target.getClass().getName(),
                        method.getName(),
                        ex.getMessage());
            }
        }
        return null;
    }

    static String normalizeName(String rawName) {
        String candidate = rawName == null ? "" : rawName.trim();
        if (candidate.isEmpty()) {
            candidate = "sandbox_capability";
        }
        String normalized = candidate
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-zA-Z0-9_-]", "_")
                .replaceAll("_+", "_");
        if (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            normalized = "sandbox_capability";
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static String defaultName(Class<?> type) {
        String simpleName = type.getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            return "sandbox_capability";
        }
        return simpleName
                .replace("SandboxCapability", "")
                .replace("Capability", "")
                .replace("Provider", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    String name() {
        return name;
    }

    String originalName() {
        return originalName;
    }

    String description() {
        return description;
    }

    Map<String, Object> inputSchema() {
        return inputSchema;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SandboxCapabilityAdapter adapter)) {
            return false;
        }
        return Objects.equals(name, adapter.name);
    }
}
