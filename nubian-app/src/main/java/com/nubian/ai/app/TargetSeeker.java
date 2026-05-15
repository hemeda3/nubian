package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Click-target sub-agent. The main actor declares a target by description
 * (e.g. "the Layer menu in the top menubar"); this seeker takes over until
 * either the click visibly lands on the described target or the attempt
 * budget is exhausted.
 *
 * <p>Each attempt is one round trip to a small VLM ({@code flash-lite} by default):
 * <ol>
 *   <li>Take a fresh screenshot.</li>
 *   <li>Ask the VLM: "Where on this screenshot is &lt;target&gt;? If my last click
 *       at (lx, ly) already hit it, say so." → JSON {done, x, y}.</li>
 *   <li>If done=true, return success with the resolved (x, y).</li>
 *   <li>Otherwise click at (x, y), record it as last click, loop.</li>
 * </ol>
 *
 * <p>The seeker is intentionally narrow: it does not type, scroll, or open
 * menus — only clicks at points. The main agent regains control with the
 * final (x, y) and a hit/give-up status.
 */
@Component("appTargetSeeker")
public final class TargetSeeker {

    private static final Logger log = LoggerFactory.getLogger(TargetSeeker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM = """
            You are a click-target sub-agent. Each turn the user gives you:
              - target: a short description of what to click,
              - last_click: (lx, ly) of your previous click attempt, or null if first attempt,
              - the current screenshot.
            Inspect the screenshot. Reason from the actual pixels, never invent elements.
            Output ONE JSON object on a single line, no prose:
              {"done": true,  "x": <int>, "y": <int>, "reason": "<one short sentence naming the visible state>"}
              when the last click already landed on the target (target is highlighted / its menu opened / button pressed in).
              {"done": false, "x": <int>, "y": <int>, "reason": "<one short sentence on where you see the target now>"}
              when you need ANOTHER click — give the absolute pixel coordinates of the target you can see RIGHT NOW.
              {"done": false, "x": -1, "y": -1, "reason": "target not visible in the screenshot"}
              when the target is not on screen at all.
            """;

    private final LlmClient llm;
    private final Sandbox sandbox;
    private final String model;
    private final int maxAttempts;
    private final int maxTokens;
    private final boolean enabled;

    public TargetSeeker(LlmClient llm, Sandbox sandbox,
            @Value("${nubian.agent.seeker-model:google/gemini-2.5-flash-lite}") String model,
            @Value("${nubian.agent.seeker-max-attempts:5}") int maxAttempts,
            @Value("${nubian.agent.seeker-max-tokens:256}") int maxTokens,
            @Value("${nubian.agent.seeker-enabled:false}") boolean enabled) {
        this.llm = llm;
        this.sandbox = sandbox;
        this.model = model;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxTokens = Math.max(64, maxTokens);
        this.enabled = enabled;
        log.info("[seeker] model={} maxAttempts={} enabled={}", model, this.maxAttempts, enabled);
    }

    public boolean enabled() { return enabled; }

    public record SeekResult(boolean hit, int x, int y, int attempts, String summary) {}

    /**
     * Loops until the seeker confirms a click landed on the target, or {@link #maxAttempts}
     * attempts are exhausted. Returns the final (x, y) the seeker last clicked, plus
     * whether it self-reported a hit.
     */
    public SeekResult seekAndClick(String target, Integer initialX, Integer initialY) {
        if (!enabled) return new SeekResult(false, -1, -1, 0, "seeker disabled");
        if (target == null || target.isBlank()) {
            return new SeekResult(false, -1, -1, 0, "empty target description");
        }
        Integer lastX = initialX;
        Integer lastY = initialY;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            byte[] shot = sandbox.screenshot();
            if (shot == null || shot.length == 0) {
                return new SeekResult(false, lastX == null ? -1 : lastX,
                        lastY == null ? -1 : lastY, attempt - 1,
                        "could not capture screenshot at attempt " + attempt);
            }
            JsonNode reply = askSeeker(shot, target, lastX, lastY);
            if (reply == null) {
                return new SeekResult(false, lastX == null ? -1 : lastX,
                        lastY == null ? -1 : lastY, attempt - 1,
                        "seeker model gave no parseable JSON at attempt " + attempt);
            }
            boolean done = reply.path("done").asBoolean(false);
            int x = reply.path("x").asInt(-1);
            int y = reply.path("y").asInt(-1);
            String reason = reply.path("reason").asText("");
            if (done && x >= 0 && y >= 0) {
                return new SeekResult(true, x, y, attempt - 1,
                        "hit on attempt " + (attempt - 1) + " at (" + x + ", " + y + ") — " + reason);
            }
            if (x < 0 || y < 0) {
                return new SeekResult(false, lastX == null ? -1 : lastX,
                        lastY == null ? -1 : lastY, attempt - 1,
                        "seeker says target not visible: " + reason);
            }
            log.info("[seeker] attempt {}/{} clicking ({}, {}) — {}",
                    attempt, maxAttempts, x, y, reason);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("x", x);
            body.put("y", y);
            body.put("button", "left");
            Sandbox.Response r = sandbox.hands("click", body);
            if (!r.ok()) {
                return new SeekResult(false, x, y, attempt,
                        "click " + x + "," + y + " failed HTTP " + r.status());
            }
            lastX = x;
            lastY = y;
        }
        // Run out of attempts — return last coords without a confirmed hit.
        return new SeekResult(false, lastX == null ? -1 : lastX,
                lastY == null ? -1 : lastY, maxAttempts,
                "exhausted " + maxAttempts + " attempts without seeker confirming hit");
    }

    private JsonNode askSeeker(byte[] shot, String target, Integer lastX, Integer lastY) {
        String userText = "target: " + target.trim()
                + "\nlast_click: " + (lastX == null ? "null" : "(" + lastX + ", " + lastY + ")")
                + "\nReply with the JSON object described in the system prompt only.";
        try {
            LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, model,
                    List.of(LlmClient.Message.system(SYSTEM),
                            LlmClient.Message.userImage(userText, shot)),
                    0.0, maxTokens);
            String text = reply.text() == null ? "" : reply.text().trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.debug("[seeker] no JSON in reply: {}", text);
                return null;
            }
            return MAPPER.readTree(text.substring(start, end + 1));
        } catch (Exception ex) {
            log.warn("[seeker] askSeeker failed: {}", ex.toString());
            return null;
        }
    }
}
