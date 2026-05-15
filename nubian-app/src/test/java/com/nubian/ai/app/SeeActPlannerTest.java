package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeeActPlannerTest {

    @Test
    void parsesSemanticClickTarget() {
        SeeActPlanner.Step step = SeeActPlanner.parseStep("""
                {"action":"click","checkpoint_id":"1","element_description":"the blue submit button","reason":"visible"}
                """);

        assertEquals("click", step.action());
        assertEquals("1", step.checkpointId());
        assertEquals("the blue submit button", step.elementDescription());
        assertTrue(step.isPointerAction());
        assertFalse(step.hasCoords());
    }

    @Test
    void parsesFlashOnlyCoordinateFallback() {
        SeeActPlanner.Step step = SeeActPlanner.parseStep("""
                {"action":"click","x":540,"y":920,"reason":"UGround disabled"}
                """);

        assertEquals("click", step.action());
        assertEquals(540, step.x());
        assertEquals(920, step.y());
        assertTrue(step.hasCoords());
    }

    @Test
    void parsesTypeTextReplaceMode() {
        SeeActPlanner.Step step = SeeActPlanner.parseStep("""
                {"action":"type_text","element_description":"Name field","text_to_type":"/workspace/agent-demo/hello.odt","mode":"replace"}
                """);

        assertEquals("type_text", step.action());
        assertEquals("/workspace/agent-demo/hello.odt", step.textToType());
        assertEquals("replace", step.mode());
        assertTrue(step.isType());
    }

    @Test
    void parsesLaunchDesktopFileAlias() {
        SeeActPlanner.Step step = SeeActPlanner.parseStep("""
                {"action":"launch_app","desktop_file":"/usr/share/applications/example-editor.desktop","reason":"exact catalog entry"}
                """);

        assertEquals("launch_app", step.action());
        assertNull(step.target());
        assertEquals("/usr/share/applications/example-editor.desktop", step.desktopFile());
    }

    @Test
    void parsesLaunchNameSeparatelyFromDesktopFile() {
        SeeActPlanner.Step step = SeeActPlanner.parseStep("""
                {"action":"launch_app","name":"Example Editor","reason":"exact catalog entry"}
                """);

        assertEquals("launch_app", step.action());
        assertEquals("Example Editor", step.target());
        assertNull(step.desktopFile());
    }

    @Test
    void parsesRecursiveSubtasks() {
        SeeActPlanner.Step step = SeeActPlanner.parseStep("""
                {
                  "action":"subdivide_checkpoint",
                  "checkpoint_id":"2",
                  "subtasks":[
                    {"text":"close blocking dialog"},
                    {"text":"open adjustment menu"},
                    {"text":"apply conversion"}
                  ],
                  "reason":"checkpoint is stuck"
                }
                """);

        assertEquals("subdivide_checkpoint", step.action());
        assertEquals("2", step.checkpointId());
        assertEquals(3, step.subtasks().size());
        assertEquals("close blocking dialog", step.subtasks().get(0).text());
    }

    @Test
    void parsesChecklistAndVisibleEvidenceUpdates() {
        SeeActPlanner.Step step = SeeActPlanner.parseStep("""
                {
                  "observation":"source image is visible",
                  "goal_link":"build logo outline",
                  "goal_trace":"open source -> source available -> build logo outline",
                  "assumption":"source is loaded",
                  "verified_by":"image visible on canvas",
                  "checklist":[
                    {"id":"1","text":"source is visible","done":false},
                    {"id":"2","text":"output is saved","done":false}
                  ],
                  "checklist_updates":[
                    {"id":"1","done":true,"evidence":"source visible on screen"}
                  ],
                  "action":"hotkey",
                  "combo":"ctrl+o",
                  "reason":"next"
                }
                """);

        assertEquals(2, step.checklist().size());
        assertEquals("source is visible", step.checklist().get(0).text());
        assertEquals(1, step.checklistUpdates().size());
        assertTrue(step.checklistUpdates().get(0).done());
        assertEquals("source visible on screen", step.checklistUpdates().get(0).evidence());
        assertEquals("source image is visible", step.observation());
        assertEquals("build logo outline", step.goalLink());
        assertEquals("open source -> source available -> build logo outline", step.goalTrace());
        assertEquals("source is loaded", step.assumption());
        assertEquals("image visible on canvas", step.verifiedBy());
    }
}
