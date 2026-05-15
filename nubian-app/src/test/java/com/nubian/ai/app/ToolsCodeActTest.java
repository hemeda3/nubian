package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolsCodeActTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final byte[] FAKE_PNG = png();
    private static final byte[] INVALID_PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};

    private static class StubSandbox extends Sandbox {
        final List<Map<String, Object>> hands = new ArrayList<>();
        String launchedApp;
        String launchedDesktopFile;
        String launchedExec;
        String writtenPath;
        String writtenContent;
        String readPath;
        boolean failBareGimpLaunch;

        StubSandbox() {
            super("http://stub:0", 1000);
        }

        @Override
        public Sandbox.Response hands(String type, Map<String, Object> args) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", type);
            if (args != null) body.putAll(args);
            hands.add(body);
            return ok("{\"ok\":true}");
        }

        @Override
        public Sandbox.Response launchApp(String name) {
            launchedApp = name;
            if (failBareGimpLaunch && "gimp".equalsIgnoreCase(name)) {
                return new Sandbox.Response(false, 404, "{\"error\":\"app not found\"}", null);
            }
            return ok("{\"ok\":true}");
        }

        @Override
        public Sandbox.Response launchDesktopApp(String name, String desktopFile, String exec) {
            launchedApp = name;
            launchedDesktopFile = desktopFile;
            launchedExec = exec;
            return ok("{\"ok\":true}");
        }

        @Override
        public Sandbox.Response activateWindow(String name, String title, String xid) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("title", title);
            body.put("xid", xid);
            hands.add(body);
            return ok("{\"ok\":true}");
        }

        @Override
        public List<Sandbox.DesktopApp> appsCatalog(int limit) {
            return List.of(
                    new Sandbox.DesktopApp(
                            "Example Image Editor",
                            "/usr/share/applications/example-image-editor.desktop",
                            "example-image-editor",
                            "Create images and edit photographs"),
                    new Sandbox.DesktopApp(
                            "GNU Image Manipulation Program",
                            "/usr/share/applications/gimp.desktop",
                            "gimp-2.10",
                            "Create images and edit photographs"));
        }

        @Override
        public Sandbox.Response writeFile(String path, String content) {
            writtenPath = path;
            writtenContent = content;
            return ok("{\"ok\":true}");
        }

        @Override
        public Sandbox.Response readFile(String path) {
            readPath = path;
            return ok("{\"content\":\"hello\",\"ok\":true,\"path\":\"" + path + "\"}");
        }

        @Override
        public byte[] screenshot() {
            return FAKE_PNG;
        }
    }

    private static final class FlakyScreenshotSandbox extends StubSandbox {
        int screenshots;

        @Override
        public byte[] screenshot() {
            screenshots++;
            return screenshots == 1 ? INVALID_PNG : FAKE_PNG;
        }
    }

    private static Sandbox.Response ok(String body) {
        return new Sandbox.Response(true, 200, body, null);
    }

    private static JsonNode json(String raw) {
        try {
            return M.readTree(raw);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static byte[] png() {
        try {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    img.setRGB(x, y, Color.WHITE.getRGB());
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void click_routes_to_structured_hands_action() {
        StubSandbox sb = new StubSandbox();
        Tools tools = new Tools(sb, new Prompts(), null);

        Tools.Outcome out = tools.invoke("click", json("{\"x\":33,\"y\":531}"));

        assertTrue(out.ok());
        assertArrayEquals(FAKE_PNG, out.screenshotPng());
        assertEquals(2, sb.hands.size());
        assertEquals("move", sb.hands.get(0).get("type"));
        assertEquals(33, sb.hands.get(0).get("x"));
        assertEquals(531, sb.hands.get(0).get("y"));
        assertEquals("long_click", sb.hands.get(1).get("type"));
        assertEquals(33, sb.hands.get(1).get("x"));
        assertEquals(531, sb.hands.get(1).get("y"));
        assertEquals("left", sb.hands.get(1).get("button"));
        assertEquals(250, sb.hands.get(1).get("duration_ms"));
    }

    @Test
    void low_delta_click_reports_metadata_without_failing_tool() {
        StubSandbox sb = new StubSandbox();
        Tools tools = new Tools(sb, new Prompts(), null);

        Tools.Outcome out = tools.withRawScreenshots(() ->
                tools.invoke("click", json("{\"x\":33,\"y\":531}")));

        assertTrue(out.ok());
        assertEquals("false", out.metadata().get("visible_change"));
        assertEquals("click left at (33, 531)", out.summary());
    }

    @Test
    void rawScreenshotRetriesInvalidBytesBeforeReturningToPlanner() {
        FlakyScreenshotSandbox sb = new FlakyScreenshotSandbox();
        Tools tools = new Tools(sb, new Prompts(), null);

        byte[] png = tools.captureRawScreenshot();

        assertArrayEquals(FAKE_PNG, png);
        assertEquals(2, sb.screenshots);
    }

    @Test
    void type_replace_is_human_keyboard_then_type() {
        StubSandbox sb = new StubSandbox();
        Tools tools = new Tools(sb, new Prompts(), null);

        Tools.Outcome out = tools.invoke("type_text",
                json("{\"text\":\"https://news.ycombinator.com\",\"mode\":\"replace\"}"));

        assertTrue(out.ok());
        assertEquals(3, sb.hands.size());
        assertEquals("hotkey", sb.hands.get(0).get("type"));
        assertEquals(List.of("ctrl", "a"), sb.hands.get(0).get("keys"));
        assertEquals("hotkey", sb.hands.get(1).get("type"));
        assertEquals(List.of("backspace"), sb.hands.get(1).get("keys"));
        assertEquals("type", sb.hands.get(2).get("type"));
        assertEquals("https://news.ycombinator.com", sb.hands.get(2).get("text"));
    }

    @Test
    void hotkey_accepts_key_array() {
        StubSandbox sb = new StubSandbox();
        Tools tools = new Tools(sb, new Prompts(), null);

        Tools.Outcome out = tools.invoke("hotkey", json("{\"keys\":[\"ctrl\",\"l\"]}"));

        assertTrue(out.ok());
        assertEquals("hotkey", sb.hands.get(0).get("type"));
        assertEquals(List.of("ctrl", "l"), sb.hands.get(0).get("keys"));
    }

    @Test
    void python_is_not_a_public_tool() {
        Tools tools = new Tools(new StubSandbox(), new Prompts(), null);

        Tools.Outcome out = tools.invoke("python", json("{\"code\":\"import selenium\"}"));

        assertFalse(out.ok());
        assertTrue(out.summary().contains("disabled"), out.summary());
    }

    @Test
    void launch_read_write_done_and_screenshot_are_supported() {
        StubSandbox sb = new StubSandbox();
        Tools tools = new Tools(sb, new Prompts(), null);

        assertTrue(tools.invoke("launch_app", json("{\"name\":\"Example Browser\"}")).ok());
        assertEquals("Example Browser", sb.launchedApp);

        sb.failBareGimpLaunch = true;
        assertTrue(tools.invoke("launch_app", json("{\"name\":\"gimp\"}")).ok());
        assertEquals("GNU Image Manipulation Program", sb.launchedApp);
        assertEquals("/usr/share/applications/gimp.desktop", sb.launchedDesktopFile);

        Tools.Outcome apps = tools.invoke("list_apps", json("{}"));
        assertTrue(apps.ok());
        assertTrue(apps.summary().contains("Example Image Editor"));
        assertTrue(apps.summary().contains("/usr/share/applications/example-image-editor.desktop"));

        assertTrue(tools.invoke("launch_app",
                json("{\"desktop_file\":\"/usr/share/applications/example-image-editor.desktop\"}")).ok());
        assertEquals("/usr/share/applications/example-image-editor.desktop", sb.launchedDesktopFile);

        Tools.Outcome activated = tools.invoke("activate_window", json("{\"name\":\"GIMP\"}"));
        assertTrue(activated.ok());
        assertEquals("GIMP", sb.hands.get(0).get("name"));

        Tools.Outcome closed = tools.invoke("close_window", json("{\"name\":\"GIMP\"}"));
        assertTrue(closed.ok());
        assertEquals("close_window", sb.hands.get(1).get("type"));
        assertEquals("GIMP", sb.hands.get(1).get("name"));

        assertTrue(tools.invoke("write_file",
                json("{\"path\":\"/workspace/agent-demo/result.md\",\"content\":\"# ok\\n\"}")).ok());
        assertEquals("/workspace/agent-demo/result.md", sb.writtenPath);
        assertEquals("# ok\n", sb.writtenContent);

        Tools.Outcome read = tools.invoke("read_file", json("{\"path\":\"/workspace/agent-demo/result.md\"}"));
        assertTrue(read.ok());
        assertEquals("/workspace/agent-demo/result.md", sb.readPath);
        assertTrue(read.summary().contains("hello"));

        Tools.Outcome done = tools.invoke("done", json("{\"summary\":\"finished\"}"));
        assertTrue(done.done());
        assertEquals("finished", done.summary());

        Tools.Outcome shot = tools.invoke("screenshot", M.createObjectNode());
        assertTrue(shot.ok());
        assertArrayEquals(FAKE_PNG, shot.screenshotPng());
    }

    @Test
    void done_requires_summary() {
        Tools tools = new Tools(new StubSandbox(), new Prompts(), null);

        Tools.Outcome out = tools.invoke("done", json("{}"));

        assertFalse(out.ok());
        assertTrue(out.summary().contains("summary is required"), out.summary());
    }

    @Test
    void unknown_tool_lists_allowed_contract() {
        Tools tools = new Tools(new StubSandbox(), new Prompts(), null);

        Tools.Outcome out = tools.invoke("selenium", new ObjectNode(M.getNodeFactory()));

        assertFalse(out.ok());
        assertTrue(out.summary().contains("Allowed tools"), out.summary());
        assertTrue(out.summary().contains("click"), out.summary());
    }
}
