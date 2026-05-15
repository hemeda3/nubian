package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Peer-advisor: every N planner calls, hand a compact log of recent turns
 * + the active plan step to a small LLM and ask for tips, a plan edit, or
 * "continue, all fine". The result becomes a short string the main planner
 * sees on its next turn — no tool actions, no new plan items, no
 * recursion.
 *
 * <p>Opt-in. Default off. Cost target: ~1 short text call per N planner
 * turns (~800 tokens, &lt; $0.0005 with Flash-Lite).
 */
@Component("appSupervisorAdvisor")
public final class SupervisorAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAdvisor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${nubian.agent.supervisor.enabled:false}")
    private boolean enabled = false;

    @Value("${nubian.agent.supervisor.interval:10}")
    private int interval = 10;

    @Value("${nubian.agent.supervisor.model:gemini-3.1-pro-preview}")
    private String model = "gemini-3.1-pro-preview";

    @Value("${nubian.agent.supervisor.max-tokens:512}")
    private int maxTokens = 512;

    @Autowired
    private LlmClient llm;

    public boolean enabled() { return enabled && llm != null && interval > 0; }

    public int interval() { return Math.max(1, interval); }

    public String model() { return model; }

    public enum Kind { CONTINUE, MODIFY, ADVANCE, EXECUTE }

    /** One concrete action the supervisor wants the runtime to dispatch
     *  directly via Tools.invoke. Restricted to keyboard-only verbs
     *  (no click — those need vision-grounded coords the supervisor
     *  does not produce). */
    public record ExecAction(String name, ObjectNode argsNode, String reason) {}

    public record Verdict(Kind kind, String newPlanText, String reason, List<ExecAction> actions) {
        public Verdict(Kind kind, String newPlanText, String reason) {
            this(kind, newPlanText, reason, List.of());
        }
        public boolean hasEdit() {
            return kind == Kind.MODIFY && newPlanText != null && !newPlanText.isBlank();
        }
        public boolean isAdvance() {
            return kind == Kind.ADVANCE;
        }
        public boolean hasActions() {
            return kind == Kind.EXECUTE && actions != null && !actions.isEmpty();
        }
    }

    /** Whitelist for EXECUTE actions. Click is excluded — supervisor has
     *  no vision pipeline to ground pixel coordinates. */
    private static final java.util.Set<String> EXEC_WHITELIST = java.util.Set.of(
            "hotkey", "key", "press_key", "enter", "escape",
            "type_text", "type",
            "activate_window", "close_window",
            "wait", "scroll");
    private static final int EXEC_MAX_ACTIONS = 6;

    private static final String SYSTEM_CALM = """
            Watch a running UI agent. Look at the active plan step it is
            trying to satisfy and its last turns. Pick exactly one verdict.

            Output JSON only, matching this schema exactly:
            {
              "verdict": "continue" | "modify" | "advance",
              "new_plan_text": "<≤25 words; replaces the active step text;
                                 ONE atomic observable end-state — describes
                                 WHAT IS TRUE when satisfied, not WHAT TO DO.
                                 No 'and' lists of actions, no 'open X then Y',
                                 no sequences of verbs. Single declarative
                                 sentence. No tool names, no advice;
                                 required when verdict=modify, else empty>",
              "reason": "<≤25 words; telemetry only; planner never sees it>"
            }

            verdict=advance means the active checkpoint is already satisfied
            by what you see in [recent_turns] — runtime marks it done and
            moves to the next. Use sparingly: only if the recent evidence
            clearly shows the acceptance-state is met. new_plan_text empty.
            """;

    private static final String SYSTEM_ANGRY = """
            URGENT — the running UI agent is stuck. A doom-loop has been
            detected (pure-navigation streak, or the same non-progress action
            repeated). A screenshot of the CURRENT screen is attached when
            available — use it as ground truth, the agent's text observations
            are unreliable. Be decisive. Pick exactly one verdict.

            Output JSON only, matching this schema exactly:
            {
              "verdict": "modify" | "advance" | "execute",
              "new_plan_text": "<≤25 words; replaces the active step text;
                                 ONE atomic observable end-state — describes
                                 WHAT IS TRUE when satisfied, not WHAT TO DO.
                                 No 'and' lists of actions, no 'open X then Y',
                                 no sequences of verbs. Single declarative
                                 sentence. No tool names, no advice;
                                 required when verdict=modify, else empty>",
              "actions": [ ... ],
              "reason": "<≤25 words; telemetry only; planner never sees it>"
            }

            advance — recent evidence already satisfies the active
              acceptance-state. Runtime marks it done and moves on.
            modify  — a clearer rewording of the acceptance-state will
              unstick the planner. Required field: new_plan_text.
            execute — take over the keyboard for 1-6 actions. Use when the
              planner is stuck on prerequisites it cannot satisfy and no
              rewording will move it. Actions run in order against whatever
              window is currently focused (use activate_window first if you
              need a different one). Required field: actions (array, 1-6).

            actions schema (only when verdict=execute):
            [
              {
                "name": "hotkey" | "type_text" | "activate_window"
                      | "close_window" | "wait" | "scroll",
                "args": { ... tool-specific args ... },
                "reason": "<≤20 words>"
              }
            ]

            args by name:
              hotkey         : { "combo": "alt+tab" | "ctrl+a" | "enter" | "f2" | ... }
              type_text      : { "text": "...literal characters to type..." }
              activate_window: { "name": "<window title or symbol>" }
              close_window   : { "name": "<window title or symbol>" }
              wait           : { "ms": 500 }
              scroll         : { "direction": "up" | "down", "amount": 3 }

            Do NOT emit click — you cannot ground coordinates.
            Do NOT return continue — the loop is real, do something.
            """;

    public Verdict consult(String activePlanText, List<String> recentTurnLines) {
        return consult(activePlanText, recentTurnLines, false, null, null);
    }

    public Verdict consult(String activePlanText, List<String> recentTurnLines, boolean angryBird) {
        return consult(activePlanText, recentTurnLines, angryBird, null, null);
    }

    public Verdict consult(String activePlanText, List<String> recentTurnLines,
                           boolean angryBird, String userTask) {
        return consult(activePlanText, recentTurnLines, angryBird, userTask, null);
    }

    /** Vision-augmented consult. When {@code angryBird} is true AND a screenshot
     *  is supplied, the supervisor sees the current screen. Calm-mode consults
     *  stay text-only to keep cost bounded.
     */
    public Verdict consult(String activePlanText, List<String> recentTurnLines,
                           boolean angryBird, String userTask, byte[] currentScreenPng) {
        if (!enabled()) {
            return new Verdict(Kind.CONTINUE, "", "");
        }
        StringBuilder body = new StringBuilder();
        if (angryBird) {
            body.append("[stuck=true] doom-loop detected; pick modify, advance, or execute.\n\n");
        }
        if (userTask != null && !userTask.isBlank()) {
            body.append("[user_task]\n").append(userTask.trim()).append("\n\n");
        }
        body.append("[active_plan_step]\n")
                .append(activePlanText == null ? "(none)" : activePlanText.trim())
                .append("\n\n[recent_turns]\n");
        if (recentTurnLines != null) {
            for (String line : recentTurnLines) {
                if (line == null || line.isBlank()) continue;
                body.append(line.trim()).append('\n');
            }
        }
        try {
            // Angry bird mode may emit an execute verdict whose type_text payload
            // is long-form prose — bump the token budget so the JSON doesn't
            // get truncated by MAX_TOKENS. Calm path keeps the cheap default.
            int budget = angryBird ? Math.max(maxTokens, 2048) : maxTokens;
            boolean useVision = angryBird && currentScreenPng != null && currentScreenPng.length > 0;
            LlmClient.Message userMsg = useVision
                    ? LlmClient.Message.userImage(body.toString(), currentScreenPng)
                    : LlmClient.Message.user(body.toString());
            LlmClient.Reply reply = llm.chat(
                    useVision ? LlmClient.Lane.VISION : LlmClient.Lane.TEXT,
                    model,
                    List.of(
                            LlmClient.Message.system(angryBird ? SYSTEM_ANGRY : SYSTEM_CALM),
                            userMsg),
                    0.0,
                    budget,
                    LlmClient.CallRole.EXTRACTOR);
            Verdict v = parse(reply.text());
            // Angry bird mode forbids CONTINUE — promote to MODIFY-no-op so caller logs it,
            // but at minimum the supervisor was forced to make a call.
            if (angryBird && v.kind() == Kind.CONTINUE) {
                v = new Verdict(Kind.CONTINUE, "", "angryBird-but-no-decision");
            }
            log.info("[supervisor] verdict={} angryBird={} new_text_len={} actions={} model={}",
                    v.kind(), angryBird,
                    v.newPlanText() == null ? 0 : v.newPlanText().length(),
                    v.actions() == null ? 0 : v.actions().size(),
                    model);
            return v;
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            log.warn("[supervisor] consult failed: {}", ex.toString());
            return new Verdict(Kind.CONTINUE, "", "");
        }
    }

    static Verdict parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new Verdict(Kind.CONTINUE, "", "");
        }
        try {
            JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
            String verdict = node.path("verdict").asText("continue").trim().toLowerCase();
            String reason = node.path("reason").asText("").trim();
            String newPlanText = node.path("new_plan_text").asText("").trim();
            Kind kind;
            switch (verdict) {
                case "modify"  -> kind = Kind.MODIFY;
                case "advance" -> kind = Kind.ADVANCE;
                case "execute" -> kind = Kind.EXECUTE;
                default        -> kind = Kind.CONTINUE;
            }
            List<ExecAction> actions = List.of();
            if (kind == Kind.EXECUTE) {
                actions = parseActions(node.path("actions"));
                if (actions.isEmpty()) {
                    // Execute with no valid actions is meaningless — downgrade.
                    kind = Kind.CONTINUE;
                }
            }
            if (kind == Kind.MODIFY && newPlanText.isEmpty()) {
                // Modify with no new text is meaningless — downgrade.
                kind = Kind.CONTINUE;
            }
            return new Verdict(kind, newPlanText, reason, actions);
        } catch (Exception ex) {
            return new Verdict(Kind.CONTINUE, "", "");
        }
    }

    /** Whitelist + arg-shape validation for the actions array. Anything off-list
     *  (e.g. "click", "run_shell") is silently dropped so the caller never
     *  gets a partially-trusted payload. */
    private static List<ExecAction> parseActions(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return List.of();
        List<ExecAction> out = new ArrayList<>();
        for (JsonNode a : arr) {
            if (out.size() >= EXEC_MAX_ACTIONS) break;
            if (!a.isObject()) continue;
            String name = a.path("name").asText("").trim().toLowerCase();
            if (name.isEmpty() || !EXEC_WHITELIST.contains(name)) continue;
            JsonNode argsRaw = a.path("args");
            ObjectNode args = argsRaw.isObject()
                    ? (ObjectNode) argsRaw
                    : MAPPER.createObjectNode();
            String reason = a.path("reason").asText("").trim();
            out.add(new ExecAction(name, args, reason));
        }
        return List.copyOf(out);
    }
}
