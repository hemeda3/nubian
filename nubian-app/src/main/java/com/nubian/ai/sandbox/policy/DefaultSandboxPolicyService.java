package com.nubian.ai.sandbox.policy;

import com.nubian.ai.sandbox.config.SandboxProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultSandboxPolicyService implements SandboxPolicyService {
    private static final Logger log = LoggerFactory.getLogger(DefaultSandboxPolicyService.class);
    private final SandboxProperties.Security security;

    public DefaultSandboxPolicyService(SandboxProperties properties) {
        this.security = properties == null ? new SandboxProperties.Security() : properties.getSecurity();
    }

    @Override
    public SandboxPolicyDecision evaluate(SandboxPolicyRequest request) {
        Objects.requireNonNull(request, "request is required");
        if (!security.isEnabled()) {
            return SandboxPolicyDecision.allow(Map.of("policy", "disabled"));
        }
        if (security.getDeniedOperations().contains(request.operation())) {
            return deny("Operation is denied by sandbox policy: " + request.operation(), "operation");
        }
        if (request.operation() == SandboxOperation.CREATE_SESSION) {
            String image = firstNonBlank(
                    request.attributes().get("container.image"),
                    request.attributes().get("docker.image"),
                    request.attributes().get("image"));
            if (!security.getAllowedDockerImages().isEmpty() && !security.getAllowedDockerImages().contains(image)) {
                return deny("Container image is not allowed: " + image, "container-image");
            }
            if (!security.isAllowPrivilegedDocker()
                    && booleanAttribute(request.attributes(), "container.privileged", "docker.privileged", "privileged")) {
                return deny("Privileged containers are disabled", "privileged-container");
            }
        }
        if (request.operation() == SandboxOperation.EXECUTE_COMMAND) {
            String command = request.attributes().getOrDefault("command", "");
            Set<String> deniedCommands = security.getDeniedCommands();
            if (deniedCommands.stream().anyMatch(denied -> commandMatches(command, denied))) {
                return deny("Command is denied by sandbox policy: " + command, "command");
            }
            SandboxPolicyDecision timeoutDecision = evaluateTimeout(request.attributes());
            if (!timeoutDecision.allowed()) {
                return timeoutDecision;
            }
        }
        SandboxPolicyDecision pathDecision = evaluatePathPrefixes(request);
        if (!pathDecision.allowed()) {
            return pathDecision;
        }
        SandboxPolicyDecision environmentDecision = evaluateEnvironmentKeys(request.attributes());
        if (!environmentDecision.allowed()) {
            return environmentDecision;
        }
        SandboxPolicyDecision portDecision = evaluatePortExposure(request);
        if (!portDecision.allowed()) {
            return portDecision;
        }
        return SandboxPolicyDecision.allow(Map.of("policy", "default"));
    }

    private SandboxPolicyDecision evaluatePathPrefixes(SandboxPolicyRequest request) {
        if (security.getDeniedPathPrefixes().isEmpty()) {
            return SandboxPolicyDecision.allow();
        }
        for (String path : paths(request.attributes())) {
            for (String deniedPrefix : security.getDeniedPathPrefixes()) {
                String prefix = deniedPrefix == null ? "" : deniedPrefix.trim();
                if (!prefix.isEmpty() && path.startsWith(prefix)) {
                    return deny("Path is denied by sandbox policy: " + path, "path");
                }
            }
        }
        return SandboxPolicyDecision.allow();
    }

    private SandboxPolicyDecision evaluateEnvironmentKeys(Map<String, String> attributes) {
        if (security.getDeniedEnvironmentKeys().isEmpty()) {
            return SandboxPolicyDecision.allow();
        }
        for (String attributeKey : attributes.keySet()) {
            String environmentKey = environmentKey(attributeKey);
            if (environmentKey == null) {
                continue;
            }
            for (String deniedKey : security.getDeniedEnvironmentKeys()) {
                if (environmentKey.equalsIgnoreCase(deniedKey)) {
                    return deny("Environment key is denied by sandbox policy: " + environmentKey, "environment");
                }
            }
        }
        return SandboxPolicyDecision.allow();
    }

    private SandboxPolicyDecision evaluatePortExposure(SandboxPolicyRequest request) {
        if (security.isAllowPublicPorts() || request.operation() != SandboxOperation.EXPOSE_PORT) {
            return SandboxPolicyDecision.allow();
        }
        if (booleanAttribute(request.attributes(), "publicAccess", "public", "port.public")) {
            return deny("Public port exposure is disabled", "public-port");
        }
        return SandboxPolicyDecision.allow();
    }

    private SandboxPolicyDecision evaluateTimeout(Map<String, String> attributes) {
        long maxSeconds = security.getMaxCommandTimeoutSeconds();
        if (maxSeconds <= 0) {
            return SandboxPolicyDecision.allow();
        }
        Long requestedSeconds = durationSeconds(attributes);
        if (requestedSeconds != null && requestedSeconds > maxSeconds) {
            return deny(
                    "Command timeout exceeds sandbox policy limit: " + requestedSeconds + "s > " + maxSeconds + "s",
                    "timeout");
        }
        return SandboxPolicyDecision.allow();
    }

    private static SandboxPolicyDecision deny(String reason, String rule) {
        return SandboxPolicyDecision.deny(reason, Map.of("policy", "default", "rule", rule));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean booleanAttribute(Map<String, String> attributes, String... keys) {
        for (String key : keys) {
            String value = attributes.get(key);
            if (value != null && Boolean.parseBoolean(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean commandMatches(String command, String deniedCommand) {
        String normalizedCommand = command == null ? "" : command.stripLeading();
        String normalizedDenied = deniedCommand == null ? "" : deniedCommand.trim();
        return !normalizedDenied.isEmpty() && normalizedCommand.startsWith(normalizedDenied);
    }

    private static Set<String> paths(Map<String, String> attributes) {
        Map<String, String> values = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (key != null && value != null && isPathKey(key) && !value.isBlank()) {
                values.put(key, value);
            }
        });
        return Set.copyOf(values.values());
    }

    private static boolean isPathKey(String key) {
        return key.equals("path")
                || key.equals("sourcePath")
                || key.equals("targetPath")
                || key.endsWith(".path")
                || key.endsWith("Path");
    }

    private static String environmentKey(String attributeKey) {
        if (attributeKey == null) {
            return null;
        }
        for (String prefix : Set.of("env.", "environment.", "environment:")) {
            if (attributeKey.startsWith(prefix) && attributeKey.length() > prefix.length()) {
                return attributeKey.substring(prefix.length());
            }
        }
        return null;
    }

    private static Long durationSeconds(Map<String, String> attributes) {
        String seconds = firstNonBlank(attributes.get("timeoutSeconds"), attributes.get("command.timeoutSeconds"));
        if (!seconds.isEmpty()) {
            return parseLong(seconds);
        }
        String millis = firstNonBlank(attributes.get("timeoutMillis"), attributes.get("command.timeoutMillis"));
        if (!millis.isEmpty()) {
            Long value = parseLong(millis);
            return value == null ? null : Math.max(1, (long) Math.ceil(value / 1000.0));
        }
        return null;
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException ex) {
            log.debug("parseLong fallback: {}", ex.toString());
            return null;
        }
    }
}
