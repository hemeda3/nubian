package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointSplitterTest {

    @Test
    void parsesTwoSubtasksFromValidJson() {
        List<String> out = CheckpointSplitter.parseSubtasks(
                "{\"subtasks\":[\"first state\",\"second state\"]}");
        assertEquals(List.of("first state", "second state"), out);
    }

    @Test
    void parsesThreeSubtasksAndCapsAtThree() {
        List<String> out = CheckpointSplitter.parseSubtasks(
                "{\"subtasks\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}");
        assertEquals(3, out.size());
        assertEquals("a", out.get(0));
        assertEquals("c", out.get(2));
    }

    @Test
    void rejectsSingleSubtaskAsInsufficient() {
        List<String> out = CheckpointSplitter.parseSubtasks(
                "{\"subtasks\":[\"only one\"]}");
        assertTrue(out.isEmpty());
    }

    @Test
    void rejectsEmptyOrMalformedJson() {
        assertTrue(CheckpointSplitter.parseSubtasks("").isEmpty());
        assertTrue(CheckpointSplitter.parseSubtasks(null).isEmpty());
        assertTrue(CheckpointSplitter.parseSubtasks("not json").isEmpty());
        assertTrue(CheckpointSplitter.parseSubtasks("{\"other\":[\"a\",\"b\"]}").isEmpty());
    }

    @Test
    void truncatesOverlongSubtaskTextAt120Chars() {
        String longText = "x".repeat(200);
        List<String> out = CheckpointSplitter.parseSubtasks(
                "{\"subtasks\":[\"" + longText + "\",\"second\"]}");
        assertEquals(2, out.size());
        assertEquals(120, out.get(0).length());
    }

    @Test
    void toleratesPrefixAndSuffixGarbage() {
        List<String> out = CheckpointSplitter.parseSubtasks(
                "preamble {\"subtasks\":[\"alpha\",\"beta\"]} trailing");
        assertEquals(List.of("alpha", "beta"), out);
    }
}
