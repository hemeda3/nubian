package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProActorTest {

    @Test
    void emptyText_isParseError_withReplyDiagnostics() {
        LlmClient.Reply reply = new LlmClient.Reply("", "stop", 2979, 1002, 3981, 1900);
        ProActor.Step step = ProActor.parseStep("", reply);

        assertTrue(step.isParseError());
        assertFalse(step.isTerminal());
        assertEquals("format_error", step.action());
        assertTrue(step.summary().contains("pro-actor returned empty content"));
        assertTrue(step.parseError().contains("finish_reason=stop"));
        assertTrue(step.parseError().contains("completion_tokens=1002"));
        assertTrue(step.parseError().contains("reasoning_chars=1900"));
    }

    @Test
    void parsesGoalLinkAssumptionAndVerifiedBy() {
        ProActor.Step step = ProActor.parseStep("""
                {"action":"hotkey","combo":"ctrl+s","goal_link":"save final output","goal_trace":"press save -> write file -> deliver output","assumption":"document is focused","verified_by":"active window visible","reason":"save"}
                """);

        assertEquals("hotkey", step.action());
        assertEquals("save final output", step.goalLink());
        assertEquals("press save -> write file -> deliver output", step.goalTrace());
        assertEquals("document is focused", step.assumption());
        assertEquals("active window visible", step.verifiedBy());
    }

    @Test
    void parsesLaunchDesktopFileAsStructuredField() {
        ProActor.Step step = ProActor.parseStep("""
                {"action":"launch_app","desktop_file":"/usr/share/applications/example-editor.desktop","reason":"catalog entry"}
                """);

        assertEquals("launch_app", step.action());
        assertNull(step.target());
        assertEquals("/usr/share/applications/example-editor.desktop", step.desktopFile());
    }
}
