package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentVisionGridTest {

    private static final class ScreenshotSandbox extends Sandbox {
        ScreenshotSandbox() {
            super("http://stub:0", 1000);
        }

        @Override
        public byte[] screenshot() {
            try {
                BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(img, "png", out);
                return out.toByteArray();
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void live_visual_turn_contains_screenshot_and_grid() throws Exception {
        Prompts prompts = new Prompts();
        Tools tools = new Tools(new ScreenshotSandbox(), prompts, null);
        LlmClient llm = new LlmClient("http://127.0.0.1:1", "test-key",
                "text-model", "vision-model", 100, 100, 1000, 0);
        Agent agent = new Agent(llm, tools, null, prompts, "<<default>>", 1);

        Method m = Agent.class.getDeclaredMethod("buildMessages",
                String.class, List.class, String.class, String.class);
        m.setAccessible(true);
        List<LlmClient.Message> messages = (List<LlmClient.Message>) m.invoke(agent,
                "click app", List.of(), "", "");

        LlmClient.Message visual = messages.stream()
                .filter(msg -> msg.role() == LlmClient.Role.USER && !msg.imagePngs().isEmpty())
                .findFirst()
                .orElseThrow();
        // Static coordinate-grid attachment removed once find_click/menu_path replaced
        // pixel-by-hand targeting. Live screenshot is the only image now (OCR-annotated
        // crop is added per-click as a 2nd image but only when there's a prior click result).
        assertEquals(1, visual.imagePngs().size(), "live screenshot only — no static grid");
    }
}
