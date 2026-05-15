package com.nubian.ai.sandbox.synthesis;

import java.util.Map;

public interface SandboxSynthesisService {
    SandboxRuntimeSnapshot synthesizeProvider(String providerId, Map<String, String> labels);

    SandboxSessionTimeline synthesizeSession(String providerId, String sessionId);
}
