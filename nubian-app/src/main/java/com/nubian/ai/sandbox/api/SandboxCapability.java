package com.nubian.ai.sandbox.api;

import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import java.util.Map;

/**
 * Common SPI metadata for provider-backed sandbox capabilities.
 */
public interface SandboxCapability {

    String providerId();

    SandboxCapabilityType type();

    default boolean supports(SandboxCapabilityType capabilityType) {
        return type() == capabilityType;
    }

    default Map<String, String> metadata() {
        return Map.of();
    }
}
