package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

/**
 * Lightweight click-target verifier.
 * <p>
 * Given a screenshot and the agent's planned (x,y) for a click together with the
 * natural-language reason describing what is being clicked, this component crops a
 * small region around (x,y) and asks a cheap vision model to verify whether the
 * planned click lands on the described target. If the verifier says the click is
 * wrong, it returns a corrected absolute pixel coordinate.
 */
@Component("appGrounding")
public final class Grounding {

    private static final Logger log = LoggerFactory.getLogger(Grounding.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM = """
            You are a click-target verifier for a screen-driving agent. You receive:
            - the full screenshot,
            - a small CROP centered at the agent's planned click pixel (cx, cy),
            - the natural-language description of what the agent intends to click.
            Decide if (cx, cy) lands on the described target on the FULL screenshot.
            Reply with ONE JSON object on a single line:
              {"correct": true}
            if the planned click is on the target. Otherwise:
              {"correct": false, "x": <int>, "y": <int>}
            where (x, y) is the corrected ABSOLUTE pixel on the full screenshot.
            No explanation, no markdown, no extra fields.
            """;

    private final LlmClient llm;
    private final String model;
    private final int radius;
    private final int maxTokens;

    public Grounding(LlmClient llm,
            @Value("${nubian.agent.grounding-model:google/gemini-2.5-flash-lite}") String model,
            @Value("${nubian.agent.grounding-radius:100}") int radius,
            @Value("${nubian.agent.grounding-max-tokens:128}") int maxTokens) {
        this.llm = llm;
        this.model = model;
        this.radius = Math.max(20, radius);
        this.maxTokens = Math.max(32, maxTokens);
    }

    public String model() { return model; }

    public record VerifyResult(boolean correct, int x, int y, String raw) {}

    public Optional<VerifyResult> verify(byte[] screenshot, int origX, int origY, String description) {
        if (screenshot == null || screenshot.length == 0
                || description == null || description.isBlank()) {
            return Optional.empty();
        }
        if (origX < 0 || origY < 0) return Optional.empty();
        try {
            BufferedImage full = ImageIO.read(new ByteArrayInputStream(screenshot));
            if (full == null) return Optional.empty();
            int cropX = Math.max(0, origX - radius);
            int cropY = Math.max(0, origY - radius);
            int cropW = Math.min(full.getWidth() - cropX, radius * 2);
            int cropH = Math.min(full.getHeight() - cropY, radius * 2);
            if (cropW <= 0 || cropH <= 0) return Optional.empty();
            BufferedImage crop = full.getSubimage(cropX, cropY, cropW, cropH);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(crop, "png", baos);
            byte[] cropPng = baos.toByteArray();

            String userText = "[planned_click]\nposition=(" + origX + ", " + origY + ")"
                    + "\ncrop_window=top-left=(" + cropX + ", " + cropY + ")"
                    + " size=(" + cropW + "x" + cropH + ")"
                    + "\ntarget=" + description.trim()
                    + "\n\nImage 1 is the full screenshot. Image 2 is the crop centered near the planned click. "
                    + "If (" + origX + ", " + origY + ") is on the target, reply {\"correct\":true}. "
                    + "Otherwise reply {\"correct\":false,\"x\":<abs>,\"y\":<abs>} with corrected ABSOLUTE pixels on the full screenshot.";
            LlmClient.Message user = LlmClient.Message.userImages(userText, List.of(screenshot, cropPng));
            LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, model,
                    List.of(LlmClient.Message.system(SYSTEM), user), 0.0, maxTokens);
            String text = reply.text() == null ? "" : reply.text().trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.debug("[grounding] no JSON in reply: {}", text);
                return Optional.empty();
            }
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            boolean correct = node.path("correct").asBoolean(false);
            int x = node.path("x").asInt(-1);
            int y = node.path("y").asInt(-1);
            return Optional.of(new VerifyResult(correct, x, y, text));
        } catch (Exception ex) {
            log.warn("[grounding] verify failed: {}", ex.toString());
            return Optional.empty();
        }
    }
}
