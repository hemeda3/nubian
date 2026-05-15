package com.nubian.ai.sandbox.policy;

import java.util.Map;

public record SandboxPolicyRequest(
        String providerId,
        String sessionId,
        SandboxOperation operation,
        Map<String, String> attributes) {
    public SandboxPolicyRequest {
        providerId = providerId == null ? "" : providerId;
        sessionId = sessionId == null ? "" : sessionId;
        operation = java.util.Objects.requireNonNull(operation, "operation");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
