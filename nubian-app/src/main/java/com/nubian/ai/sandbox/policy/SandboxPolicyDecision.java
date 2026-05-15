package com.nubian.ai.sandbox.policy;

import java.util.Map;

public record SandboxPolicyDecision(
        boolean allowed,
        String reason,
        Map<String, String> metadata) {
    public SandboxPolicyDecision {
        reason = reason == null ? "" : reason;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static SandboxPolicyDecision allow() {
        return new SandboxPolicyDecision(true, "allowed", Map.of());
    }

    public static SandboxPolicyDecision allow(Map<String, String> metadata) {
        return new SandboxPolicyDecision(true, "allowed", metadata);
    }

    public static SandboxPolicyDecision deny(String reason) {
        return new SandboxPolicyDecision(false, reason, Map.of());
    }

    public static SandboxPolicyDecision deny(String reason, Map<String, String> metadata) {
        return new SandboxPolicyDecision(false, reason, metadata);
    }
}
