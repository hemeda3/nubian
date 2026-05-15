package com.nubian.ai.sandbox.policy;

import java.util.Map;

public interface SandboxPolicyService {
    SandboxPolicyDecision evaluate(SandboxPolicyRequest request);

    default SandboxPolicyDecision evaluate(
            String providerId,
            String sessionId,
            SandboxOperation operation,
            Map<String, String> attributes) {
        return evaluate(new SandboxPolicyRequest(providerId, sessionId, operation, attributes));
    }

    default void requireAllowed(SandboxPolicyRequest request) {
        SandboxPolicyDecision decision = evaluate(request);
        if (!decision.allowed()) {
            throw new SecurityException(decision.reason());
        }
    }

    default SandboxPolicyDecision requireAllowedDecision(SandboxPolicyRequest request) {
        SandboxPolicyDecision decision = evaluate(request);
        if (!decision.allowed()) {
            throw new SecurityException(decision.reason());
        }
        return decision;
    }
}
