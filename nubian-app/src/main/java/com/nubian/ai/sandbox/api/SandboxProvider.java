package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Provider SPI entry point for sandbox implementations.
 */
public interface SandboxProvider {

    String providerId();

    SandboxSessionService sessions();

    default String displayName() {
        return providerId();
    }

    default Map<String, String> metadata() {
        return Map.of();
    }

    default Optional<SandboxFileSystem> fileSystem() {
        return Optional.empty();
    }

    default Optional<SandboxTerminal> terminal() {
        return Optional.empty();
    }

    default Optional<SandboxBrowser> browser() {
        return Optional.empty();
    }

    default Optional<SandboxDisplay> display() {
        return Optional.empty();
    }

    default Optional<SandboxPorts> ports() {
        return Optional.empty();
    }

    default Optional<SandboxArtifacts> artifacts() {
        return Optional.empty();
    }

    default Optional<SandboxComputer> computer() {
        return Optional.empty();
    }

    default Set<SandboxCapability> capabilities() {
        LinkedHashSet<SandboxCapability> capabilities = new LinkedHashSet<>();
        capabilities.add(sessions());
        fileSystem().ifPresent(capabilities::add);
        terminal().ifPresent(capabilities::add);
        browser().ifPresent(capabilities::add);
        display().ifPresent(capabilities::add);
        ports().ifPresent(capabilities::add);
        artifacts().ifPresent(capabilities::add);
        computer().ifPresent(capabilities::add);
        return Set.copyOf(capabilities);
    }

    default boolean supports(SandboxCapabilityType capabilityType) {
        return capability(capabilityType).isPresent();
    }

    default Optional<SandboxCapability> capability(SandboxCapabilityType capabilityType) {
        if (capabilityType == null) {
            return Optional.empty();
        }

        return switch (capabilityType) {
            case SESSION -> Optional.of(sessions());
            case FILE_SYSTEM -> fileSystem().map(SandboxCapability.class::cast);
            case TERMINAL -> terminal().map(SandboxCapability.class::cast);
            case BROWSER -> browser().map(SandboxCapability.class::cast);
            case DISPLAY -> display().map(SandboxCapability.class::cast);
            case PORTS -> ports().map(SandboxCapability.class::cast);
            case ARTIFACTS -> artifacts().map(SandboxCapability.class::cast);
            case COMPUTER -> computer().map(SandboxCapability.class::cast);
        };
    }
}
