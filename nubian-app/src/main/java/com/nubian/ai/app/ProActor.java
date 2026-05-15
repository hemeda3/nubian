package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-LLM per-turn actor. Replaces the prior StepPlanner+Picker split: one VLM
 * call sees the user task, the OmniParser-labeled screenshot, the structured element list,
 * the running progress note, and the last observation, and returns ONE JSON object that
 * fully specifies the next tool call (action + target box id / coords / text / combo / ...)
 * or terminates the run with done/fail.
 */
@Component("appProActor")
public final class ProActor {

    private static final Logger log = LoggerFactory.getLogger(ProActor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM = """
            You drive a desktop. Each turn you receive:
              (a) the original user task,
              (b) a running progress note (may be empty),
              (c) the last observation (tool ok/fail + summary),
              (d) a screenshot, which may be labeled by OmniParser-v2 when boxes are
                  available,
              (e) a JSON list of boxes: id, type, content, bbox. The list may be empty.

            Output EXACTLY ONE JSON object — no prose, no code fence — describing the next
            single action.
            Every object includes "goal_link": 3-4 words showing how the action helps
            the user's real final goal. This is visible trace text, not hidden reasoning.
            Every object includes "goal_trace": "current action because immediate UI effect
            because active checkpoint because final user goal". Example: change red field
            color because the form becomes readable because the form can be submitted
            because registration completes. If the chain is weak, choose another action.
            Every object includes "assumption" and "verified_by" for the current plan.

            Action schema (pick ONE shape):

              click  / double_click / right_click
                {"action":"click","box":<int>,"goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","reason":"..."}
                {"action":"click","x":<int>,"y":<int>,"goal_link":"3-4 words","goal_trace":"action because effect because checkpoint because final goal","reason":"..."}
                Use "box" only when that id is present in the provided element list.
                Use raw x/y for canvas pixels, drag handles, or when the element list is empty.

              type_text
                {"action":"type_text","text":"<string>","goal_link":"3-4 words","reason":"..."}
                Optional: "mode":"clipboard"|"keystroke" (default keystroke).

              hotkey
                {"action":"hotkey","combo":"ctrl+shift+d","goal_link":"3-4 words","reason":"..."}

              scroll
                {"action":"scroll","dx":0,"dy":-300,"reason":"..."}

              wait
                {"action":"wait","ms":500,"reason":"..."}

              launch_app
                {"action":"launch_app","name":"exact returned app name","reason":"..."}
                {"action":"launch_app","desktop_file":"/full/path/from/catalog.desktop","reason":"..."}
                {"action":"launch_app","exec":"exact returned exec command","reason":"..."}

              list_apps
                {"action":"list_apps","query":"short app name or category","reason":"need exact installed app name"}

              write_file
                {"action":"write_file","path":"/workspace/agent-demo/x.txt","content":"...","reason":"..."}

              read_file
                {"action":"read_file","path":"/workspace/agent-demo/x.txt","reason":"..."}

              done    (terminal — only after the visible end state of the user request is achieved)
                {"action":"done","summary":"<one short sentence>"}

              fail    (terminal — only when the task is provably impossible from current state)
                {"action":"fail","summary":"<why>"}

            Hard rules:
              - Read every listed box's content + bbox before picking. If the element list
                is empty, no box ids are available this turn.
              - Inventing a box id that is not in the list is a bug. If unsure, pick another
                action (open a menu, scroll, wait) instead of guessing.
              - Do not invent buttons or controls. Visible text is not a button unless the
                screenshot shows button/control geometry.
              - Use list_apps before launch_app unless the last observation already contains
                the exact catalog name, desktop_file, or exec. When emitting launch_app,
                copy exactly one returned field: name, desktop_file (full path), or exec.
              - After one failed attempt on one route, choose a different route.
              - Do not output python, shell, selenium, or invented data. JSON only.
              - Be concrete: "reason" should reference the previous observation or the visible
                screen state, not restate the user task.
            """;

    private final LlmClient llm;

    @Value("${nubian.agent.pro-actor.model:google/gemini-2.5-flash-lite}")
    private String model = "google/gemini-2.5-flash-lite";

    @Value("${nubian.agent.pro-actor.max-tokens:8192}")
    private int maxTokens = 8192;

    @Value("${nubian.agent.pro-actor.element-cap:160}")
    private int elementCap = 160;

    public ProActor(LlmClient llm) {
        this.llm = llm;
    }

    public String model() { return LlmClient.costSafeModel(model); }

    public Step next(String userTask, String progressNote, String lastObservation,
            byte[] labeledPng, List<OmniParserClient.Element> elements) {
        List<OmniParserClient.Element> capped = elements == null ? List.of()
                : (elements.size() <= elementCap ? elements : elements.subList(0, elementCap));

        StringBuilder body = new StringBuilder();
        body.append("[user task]\n").append(userTask == null ? "" : userTask.trim()).append('\n');
        if (progressNote != null && !progressNote.isBlank()) {
            body.append("\n[progress so far]\n").append(progressNote.trim()).append('\n');
        }
        if (lastObservation != null && !lastObservation.isBlank()) {
            body.append("\n[last observation]\n").append(lastObservation.trim()).append('\n');
        }
        body.append("\n[elements (id, type, content, bbox xyxy in screenshot space)]\n");
        if (capped.isEmpty()) {
            body.append("  NONE. Do not emit a box id this turn; use x/y coordinates or keyboard actions.\n");
        } else {
            for (OmniParserClient.Element e : capped) {
                body.append("  ").append(e.id())
                        .append(" ").append(e.type())
                        .append(" '").append(truncate(e.content(), 60).replace('\'', '`')).append("'")
                        .append(" [").append(e.x1()).append(',').append(e.y1())
                        .append(',').append(e.x2()).append(',').append(e.y2()).append("]\n");
            }
        }
        if (elements != null && elements.size() > elementCap) {
            body.append("  ... ").append(elements.size() - elementCap).append(" more truncated\n");
        }
        body.append("\n[task]\nEmit the next action JSON object.\n");

        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(SYSTEM));
        if (labeledPng != null && labeledPng.length > 0) {
            messages.add(LlmClient.Message.userImage(body.toString(), labeledPng));
        } else {
            messages.add(LlmClient.Message.user(body.toString()));
        }

        String useModel = LlmClient.costSafeModel(model);
        LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, useModel, messages, 0.0, maxTokens);
        String raw = reply.text() == null ? "" : reply.text().trim();
        Step parsed = parseStep(raw, reply);
        log.info("[pro-actor] {} (model={} tokens={})", parsed, useModel, reply.totalTokens());
        return parsed;
    }

    static Step parseStep(String raw) {
        return parseStep(raw, "");
    }

    static Step parseStep(String raw, String finishReason) {
        if (raw == null || raw.isBlank()) {
            String suffix = finishReason == null || finishReason.isBlank()
                    ? "" : " (finish_reason=" + finishReason + ")";
            return Step.parseError("pro-actor returned empty text" + suffix);
        }
        return parseJsonStep(raw);
    }

    static Step parseStep(String raw, LlmClient.Reply reply) {
        if (raw == null || raw.isBlank()) {
            String suffix = reply == null ? "" : " (finish_reason=" + reply.finishReason()
                    + ", prompt_tokens=" + reply.promptTokens()
                    + ", completion_tokens=" + reply.completionTokens()
                    + ", total_tokens=" + reply.totalTokens()
                    + ", reasoning_chars=" + reply.reasoningChars() + ")";
            return Step.parseError("pro-actor returned empty content" + suffix);
        }
        return parseJsonStep(raw);
    }

    private static Step parseJsonStep(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Step.parseError("pro-actor did not produce a JSON object: " + truncate(raw, 240));
        }
        try {
            JsonNode n = MAPPER.readTree(raw.substring(start, end + 1));
            String action = n.path("action").asText("").trim();
            if (action.isEmpty()) {
                return Step.parseError("pro-actor JSON missing 'action': " + truncate(raw, 240));
            }
            return new Step(
                    action,
                    n.has("box") && !n.get("box").isNull() ? n.get("box").asInt() : null,
                    n.has("x") && !n.get("x").isNull() ? n.get("x").asInt() : null,
                    n.has("y") && !n.get("y").isNull() ? n.get("y").asInt() : null,
                    optString(n, "text"),
                    optString(n, "mode"),
                    optString(n, "combo"),
                    n.has("dx") && !n.get("dx").isNull() ? n.get("dx").asInt() : null,
                    n.has("dy") && !n.get("dy").isNull() ? n.get("dy").asInt() : null,
                    n.has("ms") && !n.get("ms").isNull() ? n.get("ms").asInt() : null,
                    optString(n, "path"),
                    optString(n, "content"),
                    firstText(n, "target", "query", "name"),
                    optString(n, "desktop_file"),
                    optString(n, "exec"),
                    optString(n, "summary"),
                    optString(n, "reason"),
                    firstText(n, "goal_link", "goalLink", "roi", "why_goal"),
                    firstText(n, "goal_trace", "goalTrace", "action_trace", "trace"),
                    firstText(n, "assumption", "assumes"),
                    firstText(n, "verified_by", "verifiedBy", "evidence"),
                    null);
        } catch (Exception ex) {
            return Step.parseError("pro-actor JSON parse failed: " + ex.getMessage());
        }
    }

    private static String optString(JsonNode n, String key) {
        if (!n.has(key) || n.get(key).isNull()) return null;
        return n.get(key).asText(null);
    }

    private static String firstText(JsonNode n, String... keys) {
        if (keys == null) return null;
        for (String key : keys) {
            String value = optString(n, key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public record Step(
            String action,
            Integer box,
            Integer x, Integer y,
            String text, String mode,
            String combo,
            Integer dx, Integer dy,
            Integer ms,
            String path, String content,
            String target,
            String desktopFile,
            String exec,
            String summary,
            String reason,
            String goalLink,
            String goalTrace,
            String assumption,
            String verifiedBy,
            String parseError) {

        public boolean isClick() {
            return "click".equals(action) || "double_click".equals(action) || "right_click".equals(action);
        }

        public boolean isTerminal() { return "done".equals(action) || "fail".equals(action); }

        public boolean hasBox() { return box != null; }

        public boolean hasCoords() { return x != null && y != null; }

        public boolean isParseError() { return parseError != null; }

        static Step fail(String reason) {
            return new Step("fail", null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, reason, null, null, null, null, null, null);
        }

        static Step parseError(String why) {
            return new Step("format_error", null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, why, null, null, null, null, null, why);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Step[").append(action);
            if (box != null) sb.append(" box=").append(box);
            if (x != null && y != null) sb.append(" xy=").append(x).append(',').append(y);
            if (text != null) sb.append(" text=").append(truncate(text, 40));
            if (combo != null) sb.append(" combo=").append(combo);
            if (target != null) sb.append(" target=").append(truncate(target, 40));
            if (desktopFile != null) sb.append(" desktop_file=").append(truncate(desktopFile, 60));
            if (exec != null) sb.append(" exec=").append(truncate(exec, 60));
            if (path != null) sb.append(" path=").append(truncate(path, 60));
            if (summary != null) sb.append(" summary=").append(truncate(summary, 60));
            if (reason != null) sb.append(" reason=").append(truncate(reason, 60));
            if (goalLink != null) sb.append(" goal=").append(truncate(goalLink, 40));
            if (goalTrace != null) sb.append(" trace=").append(truncate(goalTrace, 80));
            if (assumption != null) sb.append(" assumption=").append(truncate(assumption, 60));
            if (verifiedBy != null) sb.append(" verified=").append(truncate(verifiedBy, 60));
            if (parseError != null) sb.append(" PARSE_ERROR");
            return sb.append(']').toString();
        }
    }
}
