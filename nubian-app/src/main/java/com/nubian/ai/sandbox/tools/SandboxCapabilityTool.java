package com.nubian.ai.sandbox.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.tool.SchemaType;
import com.nubian.ai.runtime.tool.Tool;
import com.nubian.ai.runtime.tool.ToolExecutionContext;
import com.nubian.ai.runtime.tool.ToolResult;
import com.nubian.ai.runtime.tool.ToolSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nubian runtime tool that dispatches calls into provider-neutral sandbox
 * capabilities.
 */
public class SandboxCapabilityTool extends Tool {
    static final String FUNCTION_NAME = "sandboxCapability";

    private static final Logger logger = LoggerFactory.getLogger(SandboxCapabilityTool.class);
    private static final Duration DEFAULT_INVOCATION_TIMEOUT = Duration.ofMinutes(15);
    private static final List<String> SELECTOR_KEYS = List.of(
            "capability",
            "capabilityName",
            "name",
            "tool",
            "operation",
            "operationName");

    private final Map<String, SandboxCapabilityAdapter> capabilitiesByName;
    private final Duration invocationTimeout;

    public SandboxCapabilityTool(Collection<SandboxCapabilityAdapter> capabilities, ObjectMapper objectMapper) {
        this(capabilities, objectMapper, DEFAULT_INVOCATION_TIMEOUT);
    }

    public SandboxCapabilityTool(
            Collection<SandboxCapabilityAdapter> capabilities,
            ObjectMapper objectMapper,
            Duration invocationTimeout) {
        super(objectMapper);
        this.capabilitiesByName = indexCapabilities(capabilities);
        this.invocationTimeout = invocationTimeout == null ? DEFAULT_INVOCATION_TIMEOUT : invocationTimeout;
        addSchema(FUNCTION_NAME, new ToolSchema(SchemaType.OPENAPI, buildSchema()));
    }

    /**
     * Execute a sandbox capability by name.
     *
     * @param arguments map containing {@code capability} and optional
     *                  {@code input}/{@code arguments}/{@code payload}
     * @return Nubian runtime tool result
     */
    public ToolResult sandboxCapability(Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        SandboxCapabilityAdapter capability = selectCapability(safeArguments);
        if (capability == null) {
            return new ToolResult(false, "Unknown sandbox capability. Available capabilities: "
                    + String.join(", ", capabilitiesByName.keySet()));
        }

        Map<String, Object> input = extractInput(safeArguments);
        ToolExecutionContext context = new ToolExecutionContext(toolCall(capability.name(), input), 0);
        try {
            Object result = capability.execute(input, context);
            return normalizeResult(awaitIfNeeded(result));
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            logger.warn("Sandbox capability {} failed: {}", capability.name(), cause.getMessage(), cause);
            return new ToolResult(false, "Sandbox capability failed: " + cause.getMessage());
        } catch (TimeoutException ex) {
            logger.warn("Sandbox capability {} timed out after {}", capability.name(), invocationTimeout);
            return new ToolResult(false, "Sandbox capability timed out after " + invocationTimeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, "Sandbox capability interrupted");
        } catch (Exception ex) {
            logger.warn("Sandbox capability {} failed: {}", capability.name(), ex.getMessage(), ex);
            return new ToolResult(false, "Sandbox capability failed: " + ex.getMessage());
        }
    }

    private static Map<String, SandboxCapabilityAdapter> indexCapabilities(
            Collection<SandboxCapabilityAdapter> capabilities) {
        Map<String, SandboxCapabilityAdapter> indexed = new LinkedHashMap<>();
        if (capabilities == null) {
            return Map.of();
        }
        for (SandboxCapabilityAdapter capability : capabilities) {
            if (capability == null) {
                continue;
            }
            indexed.putIfAbsent(capability.name(), capability);
            indexed.putIfAbsent(capability.name().toLowerCase(Locale.ROOT), capability);
            indexed.putIfAbsent(
                    SandboxCapabilityAdapter.normalizeName(capability.originalName()).toLowerCase(Locale.ROOT),
                    capability);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    private Map<String, Object> buildSchema() {
        List<SandboxCapabilityAdapter> capabilities = distinctCapabilities();
        List<String> capabilityNames = capabilities.stream()
                .map(SandboxCapabilityAdapter::name)
                .toList();

        Map<String, Object> capabilityProperty = new LinkedHashMap<>();
        capabilityProperty.put("type", "string");
        capabilityProperty.put("description", "Sandbox capability to execute.");
        if (!capabilityNames.isEmpty()) {
            capabilityProperty.put("enum", capabilityNames);
        }

        Map<String, Object> inputProperty = new LinkedHashMap<>();
        inputProperty.put("type", "object");
        inputProperty.put("description", "Capability-specific input object. " + capabilitySummary());
        inputProperty.put("additionalProperties", true);
        List<Map<String, Object>> inputVariants = capabilities.stream()
                .map(this::capabilityInputVariant)
                .toList();
        if (!inputVariants.isEmpty()) {
            inputProperty.put("oneOf", inputVariants);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("capability", capabilityProperty);
        properties.put("input", inputProperty);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("capability"));
        parameters.put("additionalProperties", true);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", FUNCTION_NAME);
        schema.put("description", "Execute one of the registered sandbox capabilities. " + capabilitySummary());
        schema.put("parameters", parameters);
        schema.put("x-sandbox-capabilities", capabilities.stream()
                .map(this::capabilityDescriptor)
                .toList());
        return schema;
    }

    private String capabilitySummary() {
        List<String> summaries = new ArrayList<>();
        for (SandboxCapabilityAdapter capability : distinctCapabilities()) {
            String summary = capability.name() + ": " + capability.description();
            if (!capability.inputSchema().isEmpty()) {
                summary += " Input schema: " + capability.inputSchema();
            }
            if (!summaries.contains(summary)) {
                summaries.add(summary);
            }
        }
        return "Available capabilities: " + String.join("; ", summaries);
    }

    private List<SandboxCapabilityAdapter> distinctCapabilities() {
        return capabilitiesByName.values().stream()
                .distinct()
                .toList();
    }

    private Map<String, Object> capabilityInputVariant(SandboxCapabilityAdapter capability) {
        Map<String, Object> schema = new LinkedHashMap<>(capability.inputSchema());
        schema.putIfAbsent("type", "object");
        schema.put("title", capability.name());
        schema.put("description", capability.description());
        return schema;
    }

    private Map<String, Object> capabilityDescriptor(SandboxCapabilityAdapter capability) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", capability.name());
        descriptor.put("originalName", capability.originalName());
        descriptor.put("description", capability.description());
        descriptor.put("inputSchema", capability.inputSchema());
        return descriptor;
    }

    private SandboxCapabilityAdapter selectCapability(Map<String, Object> arguments) {
        for (String key : SELECTOR_KEYS) {
            Object value = arguments.get(key);
            if (value instanceof String text && !text.isBlank()) {
                SandboxCapabilityAdapter capability = capabilitiesByName.get(text);
                if (capability != null) {
                    return capability;
                }
                String normalized = SandboxCapabilityAdapter.normalizeName(text);
                capability = capabilitiesByName.get(normalized);
                if (capability != null) {
                    return capability;
                }
                capability = capabilitiesByName.get(normalized.toLowerCase(Locale.ROOT));
                if (capability != null) {
                    return capability;
                }
            }
        }
        return capabilitiesByName.values().stream().distinct().limit(2).count() == 1
                ? capabilitiesByName.values().iterator().next()
                : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInput(Map<String, Object> arguments) {
        for (String key : List.of("input", "arguments", "payload", "parameters")) {
            Object value = arguments.get(key);
            if (value instanceof Map<?, ?> rawMap) {
                Map<String, Object> input = new LinkedHashMap<>();
                rawMap.forEach((mapKey, mapValue) -> {
                    if (mapKey != null) {
                        input.put(mapKey.toString(), mapValue);
                    }
                });
                return input;
            }
        }

        Map<String, Object> input = new LinkedHashMap<>(arguments);
        SELECTOR_KEYS.forEach(input::remove);
        return input;
    }

    private static Map<String, Object> toolCall(String capabilityName, Map<String, Object> input) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", FUNCTION_NAME);
        function.put("arguments", input);

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("function_name", FUNCTION_NAME);
        toolCall.put("function", function);
        toolCall.put("sandbox_capability", capabilityName);
        toolCall.put("arguments", input);
        return toolCall;
    }

    private Object awaitIfNeeded(Object result) throws Exception {
        if (result instanceof CompletionStage<?> completionStage) {
            return completionStage.toCompletableFuture().get(
                    invocationTimeout.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        return result;
    }

    private static ToolResult normalizeResult(Object result) {
        if (result instanceof ToolResult toolResult) {
            return toolResult;
        }
        ToolResult reflected = reflectToolResult(result);
        if (reflected != null) {
            return reflected;
        }
        return new ToolResult(true, result);
    }

    private static ToolResult reflectToolResult(Object result) {
        if (result == null) {
            return null;
        }
        Boolean success = readBoolean(result, List.of("isSuccess", "success", "succeeded", "isSucceeded"));
        if (success == null) {
            return null;
        }
        Object output = readValue(result, List.of(
                "getOutput",
                "output",
                "getData",
                "data",
                "getValue",
                "value",
                "getMessage",
                "message",
                "getError",
                "error"));
        return new ToolResult(success, output);
    }

    private static Boolean readBoolean(Object target, List<String> methodNames) {
        Object value = readValue(target, methodNames);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private static Object readValue(Object target, List<String> methodNames) {
        for (String methodName : methodNames) {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    logger.debug(
                            "Failed to read sandbox tool result {}.{}: {}",
                            target.getClass().getName(),
                            method.getName(),
                            ex.getMessage());
                }
            }
        }
        return null;
    }
}
