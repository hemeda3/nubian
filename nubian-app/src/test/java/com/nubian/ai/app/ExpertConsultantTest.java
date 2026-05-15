package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpertConsultantTest {

    @Test
    void questionIsTextOnlyAndFocusedOnOneIssue() {
        String question = ExpertConsultant.buildQuestion(new ExpertConsultant.Question(
                "Trace a source photograph into a logo SVG",
                "6: Select by Color",
                "Main window shows a dark thresholded image",
                "hotkey",
                "shift+o",
                "Select by Color shortcut",
                "trace the source",
                "press shortcut -> select black area -> vector path -> logo SVG",
                "tool shortcut is active",
                "main window visible",
                "pressed shift+o produced no visible change"));

        assertTrue(question.contains("[one issue]"));
        assertTrue(question.contains("Desktop UI repair expert"));
        assertTrue(question.contains("press shortcut -> select black area -> vector path -> logo SVG"));
        assertTrue(question.contains("No actions, no coordinates unless needed conceptually, no image requests"));
        assertFalse(question.contains("data:image"));
        assertFalse(question.contains("inlineData"));
    }
}
