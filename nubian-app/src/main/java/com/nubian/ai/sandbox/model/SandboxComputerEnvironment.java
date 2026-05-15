package com.nubian.ai.sandbox.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral description of a full computer sandbox.
 *
 * This is the contract agents use before deciding which concrete sandbox
 * actions to call. Providers can expose the same shape even when they only
 * support a subset of the tools.
 */
public record SandboxComputerEnvironment(
        String providerId,
        String sessionId,
        Instant inspectedAt,
        String image,
        String operatingSystem,
        List<Feature> features,
        List<Tool> tools,
        List<Directory> directories,
        List<Endpoint> endpoints,
        ResourceLimits resourceLimits,
        Map<String, String> metadata) {

    public SandboxComputerEnvironment {
        inspectedAt = inspectedAt == null ? Instant.now() : inspectedAt;
        features = features == null ? List.of() : List.copyOf(features);
        tools = tools == null ? List.of() : List.copyOf(tools);
        directories = directories == null ? List.of() : List.copyOf(directories);
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record Feature(
            String id,
            String category,
            String name,
            boolean available,
            String detail,
            Map<String, String> metadata) {

        public Feature {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record Tool(
            String id,
            String category,
            List<String> commands,
            boolean available,
            String detectedCommand,
            String detectedPath,
            String version,
            Map<String, String> metadata) {

        public Tool {
            commands = commands == null ? List.of() : List.copyOf(commands);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record Directory(
            String id,
            String path,
            String purpose,
            boolean available,
            Map<String, String> metadata) {

        public Directory {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record Endpoint(
            String id,
            String protocol,
            String url,
            Integer containerPort,
            Integer hostPort,
            boolean available,
            Map<String, String> metadata) {

        public Endpoint {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record ResourceLimits(
            String cpus,
            String memory,
            String memorySwap,
            String sharedMemory,
            String pidsLimit,
            String disk,
            String network,
            Map<String, String> metadata) {

        public ResourceLimits {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
