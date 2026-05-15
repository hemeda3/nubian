package com.nubian.ai.app.verifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceDiffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    // ------------------------------------------------------------------
    // windowsDiff
    // ------------------------------------------------------------------

    @Test
    void windowsDiff_emptyArrays_noChange() {
        EvidenceDiff.WindowsDiff diff = EvidenceDiff.windowsDiff(parse("[]"), parse("[]"));
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.titleChanged().isEmpty());
        assertEquals("no change", diff.renderForPrompt());
    }

    @Test
    void windowsDiff_nullInputs_noChange() {
        EvidenceDiff.WindowsDiff diff = EvidenceDiff.windowsDiff(null, null);
        assertEquals("no change", diff.renderForPrompt());
    }

    @Test
    void windowsDiff_added_window() {
        JsonNode before = parse("[]");
        JsonNode after = parse("[{\"wm_class\":\"firefox\",\"pid\":\"123\",\"title\":\"Mozilla Firefox\"}]");
        EvidenceDiff.WindowsDiff diff = EvidenceDiff.windowsDiff(before, after);
        assertEquals(1, diff.added().size());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.renderForPrompt().contains("+ firefox"));
    }

    @Test
    void windowsDiff_removed_window() {
        JsonNode before = parse("[{\"wm_class\":\"gedit\",\"pid\":\"42\",\"title\":\"gedit\"}]");
        JsonNode after = parse("[]");
        EvidenceDiff.WindowsDiff diff = EvidenceDiff.windowsDiff(before, after);
        assertTrue(diff.added().isEmpty());
        assertEquals(1, diff.removed().size());
        assertTrue(diff.renderForPrompt().contains("- gedit"));
    }

    @Test
    void windowsDiff_titleChanged() {
        JsonNode before = parse("[{\"wm_class\":\"gedit\",\"pid\":\"42\",\"title\":\"New File\"}]");
        JsonNode after = parse("[{\"wm_class\":\"gedit\",\"pid\":\"42\",\"title\":\"saved.txt\"}]");
        EvidenceDiff.WindowsDiff diff = EvidenceDiff.windowsDiff(before, after);
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.removed().isEmpty());
        assertEquals(1, diff.titleChanged().size());
        EvidenceDiff.TitleChange tc = diff.titleChanged().get(0);
        assertEquals("gedit", tc.wmClass());
        assertEquals("New File", tc.oldTitle());
        assertEquals("saved.txt", tc.newTitle());
        assertTrue(diff.renderForPrompt().contains("~ gedit"));
    }

    // ------------------------------------------------------------------
    // appsInstalledDiff
    // ------------------------------------------------------------------

    @Test
    void appsInstalledDiff_emptyArrays_noChange() {
        EvidenceDiff.AppsDiff diff = EvidenceDiff.appsInstalledDiff(parse("[]"), parse("[]"));
        assertEquals("no change", diff.renderForPrompt());
    }

    @Test
    void appsInstalledDiff_newly_installed() {
        JsonNode before = parse("[]");
        JsonNode after = parse("[{\"name\":\"VLC\",\"desktop_file\":\"vlc.desktop\"}]");
        EvidenceDiff.AppsDiff diff = EvidenceDiff.appsInstalledDiff(before, after);
        assertEquals(1, diff.installed().size());
        assertTrue(diff.uninstalled().isEmpty());
        assertTrue(diff.renderForPrompt().contains("+ VLC"));
    }

    @Test
    void appsInstalledDiff_uninstalled() {
        JsonNode before = parse("[{\"name\":\"VLC\",\"desktop_file\":\"vlc.desktop\"}]");
        JsonNode after = parse("[]");
        EvidenceDiff.AppsDiff diff = EvidenceDiff.appsInstalledDiff(before, after);
        assertTrue(diff.installed().isEmpty());
        assertEquals(1, diff.uninstalled().size());
        assertTrue(diff.renderForPrompt().contains("- VLC"));
    }

    @Test
    void appsInstalledDiff_same_app_no_change() {
        String json = "[{\"name\":\"Firefox\",\"desktop_file\":\"firefox.desktop\"}]";
        EvidenceDiff.AppsDiff diff = EvidenceDiff.appsInstalledDiff(parse(json), parse(json));
        assertEquals("no change", diff.renderForPrompt());
    }
}
