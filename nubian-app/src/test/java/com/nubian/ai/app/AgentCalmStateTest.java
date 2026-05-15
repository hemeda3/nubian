package com.nubian.ai.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nubian.ai.app.todo.RecursiveTodoState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentCalmStateTest {

    @Test
    void launchArgsPreserveDesktopFileField() {
        ObjectNode args = new ObjectMapper().createObjectNode();

        assertTrue(Agent.putLaunchArgs(args, null,
                "/usr/share/applications/example-editor.desktop", null));

        assertFalse(args.has("name"));
        assertEquals("/usr/share/applications/example-editor.desktop",
                args.get("desktop_file").asText());
    }

    @Test
    void checklistStartsPendingEvenIfPlannerClaimsDone() {
        RecursiveTodoState state = new RecursiveTodoState();

        state.initialize(List.of(
                new SeeActPlanner.ChecklistItem("1", "first visible checkpoint", true),
                new SeeActPlanner.ChecklistItem("2", "second visible checkpoint", false)));

        assertTrue(state.initialized());
        assertEquals(0, state.doneCount());
        assertEquals("1: first visible checkpoint", state.activeText());
        assertTrue(state.promptText().startsWith("checkpoint_id=1"));
        assertTrue(state.promptText().contains("checkpoint_text=first visible checkpoint"));
        assertTrue(state.promptText().contains("subdivide_checkpoint"));
        assertTrue(state.summary().contains("[>] 1: first visible checkpoint"));
    }

    @Test
    void backendCanStillMarkActiveCheckpointDoneForArtifactEvidence() {
        RecursiveTodoState state = new RecursiveTodoState();
        state.initialize(List.of(
                new SeeActPlanner.ChecklistItem("1", "dialog is visible", false),
                new SeeActPlanner.ChecklistItem("2", "file is saved", false)));

        assertTrue(state.markActiveDone("screen changed"));

        assertEquals(1, state.doneCount());
        assertEquals("2: file is saved", state.activeText());
        assertTrue(state.acceptedText().contains("1: dialog is visible"));
        assertTrue(state.acceptedText().contains("screen changed"));
        assertFalse(state.allDone());
    }

    @Test
    void acceptsOnlyVisibleCommandForActiveCheckpoint() {
        RecursiveTodoState state = new RecursiveTodoState();
        state.initialize(List.of(
                new SeeActPlanner.ChecklistItem("1", "window is maximized", false),
                new SeeActPlanner.ChecklistItem("2", "file is saved", false)));

        assertFalse(state.applyActiveUpdate(
                new SeeActPlanner.ChecklistUpdate("2", true, "file appears saved")));
        assertFalse(state.applyActiveUpdate(
                new SeeActPlanner.ChecklistUpdate("1", true, "")));
        assertTrue(state.applyActiveUpdate(
                new SeeActPlanner.ChecklistUpdate("1", true, "window fills the screen")));

        assertEquals(1, state.doneCount());
        assertEquals("2: file is saved", state.activeText());
    }

    @Test
    void acceptsOnlyPlansForActiveCheckpointId() {
        RecursiveTodoState state = new RecursiveTodoState();
        state.initialize(List.of(
                new SeeActPlanner.ChecklistItem("1", "window is maximized", false),
                new SeeActPlanner.ChecklistItem("2", "file is saved", false)));

        assertTrue(state.acceptsPlan("1"));
        assertTrue(state.acceptsPlan(" 1 "));
        assertFalse(state.acceptsPlan("2"));
        assertFalse(state.acceptsPlan(""));
        assertFalse(state.acceptsPlan(null));
    }

    @Test
    void recursiveTodosRunOnlyLeafAndRollUpToParent() {
        RecursiveTodoState state = new RecursiveTodoState();
        state.initialize(List.of(
                new SeeActPlanner.ChecklistItem("1", "desaturate duplicate layer", false),
                new SeeActPlanner.ChecklistItem("2", "blur duplicate layer", false)));

        assertTrue(state.pushSubtasks(List.of(
                new SeeActPlanner.ChecklistItem("a", "open Colors menu", false),
                new SeeActPlanner.ChecklistItem("b", "open Desaturate dialog", false),
                new SeeActPlanner.ChecklistItem("c", "apply desaturation", false)), "complex menu"));

        assertEquals("1.1: open Colors menu", state.activeText());
        assertTrue(state.acceptsPlan("1.1"));
        assertFalse(state.acceptsPlan("1"));

        assertTrue(state.markActiveDone("Colors menu visible"));
        assertEquals("1.2: open Desaturate dialog", state.activeText());
        assertTrue(state.markActiveDone("dialog visible"));
        assertEquals("1.3: apply desaturation", state.activeText());
        assertTrue(state.markActiveDone("image grayscale"));

        assertEquals("2: blur duplicate layer", state.activeText());
        assertTrue(state.acceptedText().contains("1: desaturate duplicate layer"));
        assertTrue(state.acceptedText().contains("1.3 ok"));
        assertTrue(state.summary().contains("[>] 2: blur duplicate layer"));
    }

    @Test
    void llmSplitterDrivesSubdivisionWithTaskAwareAcceptanceStates() {
        RecursiveTodoState state = new RecursiveTodoState();
        state.initialize(List.of(new SeeActPlanner.ChecklistItem("1",
                "GIMP is closed.", false)));

        RecursiveTodoState.Splitter splitter = (parent, reason, ctx) -> List.of(
                "No GIMP window is visible on screen",
                "GIMP process is not running",
                "Active focus is on the desktop or another app");

        assertTrue(state.pushAutoSubtasks("close-task subdivision", "", splitter));

        // Children carry leaf claims only — lineage is structural via parentId.
        // The "X for: parent" string-concat scheme that compounded across
        // depths and triggered the punctuation-split cascade is gone.
        assertEquals("1.1: No GIMP window is visible on screen", state.activeText());
        assertTrue(state.summary().contains("[ ] 1.2: GIMP process is not running"),
                () -> "summary: " + state.summary());
        assertTrue(state.summary().contains("[ ] 1.3: Active focus is on the desktop or another app"));
        assertFalse(state.summary().contains(" for: "),
                () -> "no for-suffix should appear in summary; got:\n" + state.summary());

        // Lineage is queryable as a separate block — the verifier and splitter
        // walk it at use time, never bake it into a child's text.
        String lineage = state.activeLineage();
        assertTrue(lineage.contains("[lineage]"));
        assertTrue(lineage.contains("GIMP is closed."));

        assertFalse(state.acceptsPlan("1"));
        assertTrue(state.acceptsPlan("1.1"));
    }

    @Test
    void splitterEmptyResultIsAtomicAndDoesNotSubdivide() {
        // Empty splitter result == "the parent is atomic" per the ATOMICITY
        // contract in CheckpointSplitter. The runtime no longer falls back to
        // splitting the parent text on punctuation — that fallback under the
        // old string-concat lineage produced "for: ..." sibling fragments.
        // Now the runtime honors the atomic signal and returns false so the
        // caller can pick a different route (e.g. retry, fail, or escalate).
        RecursiveTodoState state = new RecursiveTodoState();
        state.initialize(List.of(new SeeActPlanner.ChecklistItem("1",
                "Open the dialog. Pick the file. Click Open.", false)));

        RecursiveTodoState.Splitter alwaysEmpty = (parent, reason, ctx) -> List.of();

        assertFalse(state.pushAutoSubtasks("splitter says atomic", "", alwaysEmpty));
        assertEquals("1: Open the dialog. Pick the file. Click Open.", state.activeText());
        assertEquals(0, state.depth());
    }

    @Test
    void splitterReceivesTaskParentReasonAndContext() {
        RecursiveTodoState state = new RecursiveTodoState();
        state.initialize(List.of(new SeeActPlanner.ChecklistItem("1",
                "Save the document.", false)));

        java.util.concurrent.atomic.AtomicReference<String> seenParent = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> seenReason = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> seenContext = new java.util.concurrent.atomic.AtomicReference<>();
        RecursiveTodoState.Splitter recording = (parent, reason, ctx) -> {
            seenParent.set(parent);
            seenReason.set(reason);
            seenContext.set(ctx);
            return List.of("File save dialog is visible", "File path is set", "Save confirmation is visible");
        };

        state.pushAutoSubtasks("auto split", "Editor is focused", recording);

        assertEquals("Save the document.", seenParent.get());
        assertEquals("auto split", seenReason.get());
        assertEquals("Editor is focused", seenContext.get());
    }

    private static int countSubtaskLines(String summary) {
        int n = 0;
        for (String line : summary.split("\n")) {
            String t = line.trim();
            if (t.startsWith("[ ] 1.") || t.startsWith("[>] 1.") || t.startsWith("[x] 1.")) n++;
        }
        return n;
    }

    @Test
    void repairBlocksTinyCoordinateDriftOnSameFailedTarget() {
        Agent.CalmRepairState repair = new Agent.CalmRepairState();
        Agent.CallSignature failed = Agent.CallSignature.of("click", 500, 721, "");
        Agent.CallSignature drift = Agent.CallSignature.of("click", 500, 720, "");

        repair.recordNoProgress(failed, "no visual change");

        assertTrue(repair.shouldBlock(drift));
        assertEquals(1, repair.recordBlocked());
        assertTrue(repair.blockMessage("1: threshold", drift).contains("click at 500,720"));
        assertTrue(repair.blockMessage("1: threshold", drift).contains("failed once"));
        assertFalse(repair.exhausted());
    }

    @Test
    void repairDoesNotBlockDifferentPointerTarget() {
        Agent.CalmRepairState repair = new Agent.CalmRepairState();
        Agent.CallSignature failed = Agent.CallSignature.of("click", 500, 721, "");
        Agent.CallSignature other = Agent.CallSignature.of("click", 500, 740, "");

        repair.recordNoProgress(failed, "no visual change");

        assertFalse(repair.shouldBlock(other));
    }

    @Test
    void repairSubdivisionKeepsFailedTargetForbidden() {
        Agent.CalmRepairState repair = new Agent.CalmRepairState();
        Agent.CallSignature failed = Agent.CallSignature.of("click", 917, 709, "");

        repair.recordNoProgress(failed, "no visual change");
        repair.markSubdivisionRequested();
        repair.afterSubdivision();

        assertTrue(repair.shouldBlock(failed));
        assertFalse(repair.subdivisionRequested());
    }

    @Test
    void repairConsultsExpertOnlyAfterTwoNoProgressAttempts() {
        Agent.CalmRepairState repair = new Agent.CalmRepairState();
        Agent.CallSignature failed = Agent.CallSignature.of("hotkey", null, null, "shift+o");

        assertEquals(1, repair.recordNoProgress(failed, "no visible change"));
        assertFalse(repair.shouldConsultExpert(1));

        assertEquals(2, repair.recordNoProgress(failed, "still no visible change"));
        assertTrue(repair.shouldConsultExpert(2));

        repair.markExpertConsulted();

        assertFalse(repair.shouldConsultExpert(3));
    }
}
