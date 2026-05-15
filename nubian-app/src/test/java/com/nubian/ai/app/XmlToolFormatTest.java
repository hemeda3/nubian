package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XmlToolFormatTest {

    @Test
    void wellFormed_args_block_parses() {
        String resp = """
                blah
                <tools>
                  <tool name="python">
                    <reason>do thing</reason>
                    <args>{"code":"print(1)","timeout_ms":15000}</args>
                  </tool>
                </tools>
                """;
        List<XmlToolFormat.ParsedCall> calls = XmlToolFormat.parseToolCalls(resp);
        assertEquals(1, calls.size());
        XmlToolFormat.ParsedCall c = calls.get(0);
        assertEquals("python", c.name());
        assertTrue(c.argsJson().contains("\"code\""), "code key present");
        assertTrue(c.argsJson().contains("print(1)"), "code body preserved");
    }

    @Test
    void missing_close_args_tag_still_parses_to_args() {
        String resp = """
                <tools><tool name="python"><reason>open yahoo</reason><args>{"code": "import webbrowser\\nwebbrowser.open_new_tab('https://news.yahoo.com')", "timeout_ms": 15000}</tool></tools>
                """;
        List<XmlToolFormat.ParsedCall> calls = XmlToolFormat.parseToolCalls(resp);
        assertEquals(1, calls.size());
        String args = calls.get(0).argsJson();
        assertTrue(args.contains("webbrowser"),
                "args body preserved despite missing </args>: " + args);
        assertTrue(args.contains("yahoo.com"),
                "URL preserved despite missing </args>: " + args);
    }

    @Test
    void code_block_lifts_into_args_with_real_newlines() {
        String resp = """
                <tools>
                  <tool name="python">
                    <reason>scrape</reason>
                    <code timeout_ms="30000">
                import requests
                for u in ['a','b']:
                    print(u)
                    </code>
                  </tool>
                </tools>
                """;
        List<XmlToolFormat.ParsedCall> calls = XmlToolFormat.parseToolCalls(resp);
        assertEquals(1, calls.size());
        String args = calls.get(0).argsJson();
        assertTrue(args.contains("\"code\""), "code key present: " + args);
        assertTrue(args.contains("import requests"), "first line preserved: " + args);
        assertTrue(args.contains("for u in"), "loop preserved: " + args);
        assertTrue(args.contains("\"timeout_ms\":30000") || args.contains("\"timeout_ms\": 30000"),
                "timeout attr lifted: " + args);
    }

    @Test
    void code_wins_over_args_with_merge() {
        String resp = """
                <tools>
                  <tool name="python">
                    <reason>x</reason>
                    <args>{"timeout_ms": 9999, "code": "should be ignored"}</args>
                    <code>
                print("real code")
                    </code>
                  </tool>
                </tools>
                """;
        List<XmlToolFormat.ParsedCall> calls = XmlToolFormat.parseToolCalls(resp);
        assertEquals(1, calls.size());
        String args = calls.get(0).argsJson();
        assertTrue(args.contains("real code"), "<code> body wins: " + args);
        assertFalse(args.contains("should be ignored"), "<args> code key dropped: " + args);
        assertTrue(args.contains("9999"), "non-code args keys merged: " + args);
    }

    @Test
    void live_trace_missing_args_close_tag() {
        String resp = "<tools><tool name=\"python\">"
                + "<reason>Open a new browser tab using Ctrl+T.</reason>"
                + "<args>{\"code\": \"import pyautogui; pyautogui.hotkey('ctrl', 't')\", \"timeout_ms\": 15000}"
                + "</tool></tools>";
        List<XmlToolFormat.ParsedCall> calls = XmlToolFormat.parseToolCalls(resp);
        assertEquals(1, calls.size());
        String args = calls.get(0).argsJson();
        assertTrue(args.contains("hotkey"), "code body preserved: " + args);
        assertTrue(args.contains("timeout_ms"), "timeout preserved: " + args);
        assertNotEquals("{}", args, "must NOT collapse to empty");
    }

    @Test
    void no_tools_block_returns_empty() {
        assertTrue(XmlToolFormat.parseToolCalls("just chain of thought, nothing structured").isEmpty());
        assertTrue(XmlToolFormat.parseToolCalls("").isEmpty());
        assertTrue(XmlToolFormat.parseToolCalls(null).isEmpty());
    }

    /**
     * Real-trace failure: thinking model truncated at max_tokens, lost the closing
     * </tools> tag. Parser must still return the inner <tool>...</tool> calls.
     */
    @Test
    void tolerates_missing_tools_close_tag() {
        String resp = "<tools><tool name=\"launch_app\"><reason>start requested editor</reason>"
                + "<args>{\"app\":\"Example Image Editor\"}</args></tool>";
        List<XmlToolFormat.ParsedCall> calls = XmlToolFormat.parseToolCalls(resp);
        assertEquals(1, calls.size(), "must extract the one tool call despite missing </tools>");
        assertEquals("launch_app", calls.get(0).name());
        assertTrue(calls.get(0).argsJson().contains("Example Image"), calls.get(0).argsJson());
    }
}
