package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nubian.ai.app.todo.RecursiveTodoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nubian.ai.app.verifier.EvidenceDiff;
import com.nubian.ai.app.verifier.EvidenceStore;
import com.nubian.ai.app.verifier.PixelDiff;
import com.nubian.ai.app.verifier.PngAnnotator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Component("appAgent")
public final class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;
    private static final ThreadLocal<BooleanSupplier> CANCEL_CHECK = new ThreadLocal<>();

    private static final int MAX_BATCH_PER_RESPONSE = 3;
    /** Keep a strict rolling context: current screenshot + newest raw turn only.
     *  Everything older is folded into {@code runningSummary} every turn. This
     *  prevents 50+ turn runs from resending old screenshots, OCR crops, and
     *  tool logs on every planner call. */
    private static final int SUMMARY_INTERVAL = 1;
    private static final int SUMMARY_KEEP_TAIL = 1;
    /** Only the newest retained history turn may carry screenshot/grid/OCR crop. */
    private static final int IMAGE_TAIL = 1;
    private static final int MAX_EVIDENCE_NAV_MOVES = 3;

    /** Actions that destroy state — closing windows, killing apps, file deletes.
     *  These require justification from the active checkpoint text (see
     *  DESTRUCTIVE_KEYWORDS); otherwise they're rejected as conversational
     *  backtracking ("close it then open it again..." re-firing mid-task). */
    private static final java.util.Set<String> DESTRUCTIVE_ACTIONS = java.util.Set.of(
            "close_window", "window_close", "close_app", "quit_app",
            "kill_window", "kill_app", "force_close");

    /** If the active checkpoint contains any of these substrings (lowercased),
     *  destructive actions are allowed. Otherwise blocked. */
    private static final java.util.List<String> DESTRUCTIVE_KEYWORDS = java.util.List.of(
            "close", "quit", "exit", "shut", "kill", "terminate",
            "discard", "dismiss", "abandon", "reset", "restart");
    private static final java.util.List<String> DESTRUCTIVE_USER_GOAL_KEYWORDS = java.util.List.of(
            "close", "quit", "exit", "shut", "kill", "terminate");
    private static final java.util.List<String> ABSENCE_STATE_KEYWORDS = java.util.List.of(
            "no longer visible", "not visible", "gone", "absent",
            "no longer running", "not running", "closed");
    private static final String FORMAT_FEEDBACK_CALL = "__format_feedback";
    private static final String NO_TOOL_FEEDBACK =
            "FORMAT ERROR: your last response was not executed because it had no <tools> block.";
    private static final String VISION_NOTE =
            "[vision]\nTwo images attached: (1) the live screenshot LABELED by OmniParser-v2 — every detected text and clickable icon has a green bounding box and a numeric id burned in; (2) a static coordinate-grid overlay with x/y labels for the rare case you must click an unlabeled pixel.\nRead the id of the element you want from image 1 and emit click_box{id:<int>}. Java will translate the id to the bbox center pixel for you. Use raw click{x,y} only when no green box covers the target.\nWhen multiple history turns are kept, treat the OLDER turn's labeled screenshot as the BEFORE state and the NEWER as the AFTER state for the latest action.\n";
    private static final String SUMMARIZER_SYSTEM =
            "You maintain a running PROGRESS NOTE for a screen-driving agent. The user's original task is shown to the model separately on every turn — DO NOT restate it. You receive: the prior progress note (may be empty) and ONE new action turn that just happened. "
          + "Output the UPDATED progress note as ONE paragraph, max 400 chars. Cover only: (1) what concrete state has been achieved so far (files created, sheets opened, dialogs dismissed, values entered), (2) what the latest turn produced (success / no-op / failure + concrete coords/values/widgets). "
          + "Do NOT repeat the goal. Do NOT propose the next action — that is the planner's job. Do NOT enumerate everything ever tried. Drop anything that no longer informs the next decision. Replace, do not append. No preamble, no markdown, no bullets, just the paragraph.";

    private final LlmClient llm;
    private final Tools tools;
    private final Grounding grounding;
    private final Planner planner;
    private final String basePrompt;
    private final int stepBudget;
    @Value("${nubian.agent.doom-threshold:" + DoomGuard.DEFAULT_THRESHOLD + "}")
    private int doomThreshold = DoomGuard.DEFAULT_THRESHOLD;
    @Value("${nubian.agent.grounding-enabled:false}")
    private boolean groundingEnabled = false;
    @Value("${nubian.agent.summarizer-model:google/gemini-2.5-flash-lite}")
    private String summarizerModel = "google/gemini-2.5-flash-lite";
    @Value("${nubian.agent.summarizer-max-tokens:1024}")
    private int summarizerMaxTokens = 1024;
    @Value("${nubian.agent.checkpoint-verifier-model:google/gemini-2.5-flash-lite}")
    private String checkpointVerifierModel = "google/gemini-2.5-flash-lite";
    @Value("${nubian.agent.checkpoint-verifier-max-tokens:512}")
    private int checkpointVerifierMaxTokens = 512;
    @Value("${nubian.agent.observation-settle-ms:900}")
    private int observationSettleMs = 900;

    /** Selects the per-turn loop strategy.
     *  <ul>
     *    <li>{@code seeact} (default) — Gemini plans the semantic action from a raw
     *        screenshot; UGround optionally resolves the target description to x/y pixels.</li>
     *    <li>{@code pro-actor} — single Gemini call per turn that emits the full next-action
     *        JSON (legacy OmniParser box-id path).</li>
     *    <li>{@code actor} — legacy single-LLM XML &lt;tools&gt; flow.</li>
     *  </ul> */
    @Value("${nubian.agent.flow:seeact}")
    private String flow = "seeact";

    @Autowired(required = false)
    private ProActor proActor;

    @Autowired(required = false)
    private SeeActPlanner seeActPlanner;

    @Autowired(required = false)
    private UGroundClient uGround;

    @Autowired(required = false)
    private ExpertConsultant expertConsultant;

    @Autowired(required = false)
    private CheckpointSplitter checkpointSplitter;

    @Autowired(required = false)
    private GoalContractExtractor goalContractExtractor;

    @Autowired(required = false)
    private BestOfNJudge bestOfNJudge;

    /** Peer-advisor: every N planner calls, hands a compact log of recent
     *  turns + the active plan step to a small LLM. Returns "continue",
     *  "tips", or "modify". A non-empty message gets prepended to the
     *  planner's lastObservation on the NEXT turn so the planner sees the
     *  advice without us inventing new plan steps or actions. Opt-in. */
    @Autowired(required = false)
    private SupervisorAdvisor supervisorAdvisor;

    /** Best-of-N sampling for the SeeAct planner. 1 = current behaviour (single
     *  deterministic call at temperature 0). >1 = sample N candidates in parallel
     *  at {@link #bestOfNTemperature} and pick a winner via {@link BestOfNJudge}.
     *  Opt-in experiment for trace tasks where the planner's first sample is
     *  brittle (path tracing donkey-trace etc.). */
    @Value("${nubian.agent.best-of-n:1}")
    private int bestOfN = 1;

    @Value("${nubian.agent.best-of-n.temperature:0.7}")
    private double bestOfNTemperature = 0.7;

    @Autowired
    public Agent(LlmClient llm, Tools tools, Grounding grounding, Planner planner, Prompts prompts,
            @Value("${nubian.agent.base-prompt:" + DEFAULT_BASE_PROMPT_REF + "}") String basePrompt,
            @Value("${nubian.agent.max-steps:80}") int stepBudget) {
        this.llm = llm;
        this.tools = tools;
        this.grounding = grounding;
        this.planner = planner;
        String fromResource = prompts == null ? null : prompts.getString("actor.system");
        this.basePrompt = (basePrompt == null || basePrompt.isBlank() || DEFAULT_BASE_PROMPT_REF.equals(basePrompt))
                ? (fromResource == null || fromResource.isBlank() ? FALLBACK_BASE_PROMPT : fromResource)
                : basePrompt;
        this.stepBudget = Math.max(1, stepBudget);
    }

    public Agent(LlmClient llm, Tools tools, Grounding grounding, Prompts prompts,
            String basePrompt, int stepBudget) {
        this(llm, tools, grounding, null, prompts, basePrompt, stepBudget);
    }

    public Result run(String runId, String userTask, Consumer<Events.Event> emit) {
        return run(runId, userTask, emit, NEVER_CANCELLED);
    }

    Result run(String runId, String userTask, Consumer<Events.Event> emit,
            BooleanSupplier cancelRequested) {
        BooleanSupplier prior = CANCEL_CHECK.get();
        CANCEL_CHECK.set(cancelRequested == null ? NEVER_CANCELLED : cancelRequested);
        bindLlmTelemetry(runId, emit);
        verdictCache.clear();
        blameLedger.clear();
        // Reset run-scoped evidence state
        evidenceStore.clear();
        taskSeqByCheckpointId.clear();
        taskStartedTsBySeq.clear();
        taskSeqCounter = 0;
        runBaselineEvidenceJson = "";
        noChangeStrikes.clear();
        try {
            String baseline = tools.evidenceBundle(null, null);
            if (baseline != null) runBaselineEvidenceJson = baseline;
        } catch (RuntimeException ignored) {
            // baseline unavailable — proceed without it
        }
        emitStackHealth(runId, emit);
        try {
            return runLoop(runId, userTask, emit);
        } finally {
            LlmClient.unbindTelemetry();
            if (prior == null) CANCEL_CHECK.remove();
            else CANCEL_CHECK.set(prior);
        }
    }

    private void emitStackHealth(String runId, Consumer<Events.Event> emit) {
        boolean parserConfigured = tools != null && tools.omniParserEnabled();
        boolean parserOk = parserConfigured && tools.omniParserHealthy();
        boolean grounderConfigured = uGround != null && uGround.enabled();
        boolean grounderOk = grounderConfigured && uGround.healthCheck();
        emit.accept(Events.of(runId, "stack_health",
                "parser=" + (parserConfigured ? (parserOk ? "ok" : "DOWN") : "off")
                        + " · grounder=" + (grounderConfigured ? (grounderOk ? "ok" : "DOWN") : "off"),
                Events.meta()
                        .put("parserConfigured", parserConfigured)
                        .put("parserHealthy", parserOk)
                        .put("parserUrl", parserConfigured && tools != null ? tools.omniParserBaseUrl() : "")
                        .put("grounderConfigured", grounderConfigured)
                        .put("grounderHealthy", grounderOk)
                        .put("grounderUrl", grounderConfigured ? uGround.baseUrl() : "")
                        .put("geminiDirectEnabled", llm != null && llm.geminiDirectEnabled())
                        .put("geminiBaseUrl", llm == null ? "" : llm.geminiBaseUrl())
                        .put("geminiKeySource", llm == null ? "" : llm.geminiKeySource())
                        .put("geminiKeyConfigured", llm != null && llm.geminiKeyConfigured())
                        .put("geminiKeyPrefix4", llm == null ? "" : llm.geminiKeyPrefix4())
                        .put("geminiKeySha12", llm == null ? "" : llm.geminiKeyFingerprint())
                        .build()));
        if (parserConfigured && !parserOk) {
            log.warn("[agent] OmniParser configured but unreachable — agent will run without element list");
        }
        if (grounderConfigured && !grounderOk) {
            log.warn("[agent] UGround configured but unreachable — agent will run without pixel grounding");
        }
    }

    private final VerdictCache verdictCache = new VerdictCache();
    private final BlameLedger blameLedger = new BlameLedger();

    // Run-scoped evidence tracking (reset at each run start)
    private final EvidenceStore evidenceStore = new EvidenceStore();
    private final Map<String, Integer> taskSeqByCheckpointId = new HashMap<>();
    private final Map<Integer, Long> taskStartedTsBySeq = new HashMap<>();
    private int taskSeqCounter = 0;
    private String runBaselineEvidenceJson = "";

    /** Structured parse of the current run's user goal. Reset at the start of
     *  each run; empty when extraction is disabled or failed. */
    private volatile GoalContract runContract = GoalContract.empty("");
    /** Per-route consecutive no_change strikes. A single transient zero-delta (e.g. a window
     *  closing whose repaint hasn't landed yet) must not poison the ledger; require 2 strikes
     *  for the same route hash before recording NO_CHANGE. */
    private final Map<String, Integer> noChangeStrikes = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong cumulativePrompt = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cumulativeCached = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cumulativeReasoning = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cumulativeCompletion = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong cumulativeAllTokens = new java.util.concurrent.atomic.AtomicLong(0);

    private void bindLlmTelemetry(String runId, Consumer<Events.Event> emit) {
        cumulativePrompt.set(0);
        cumulativeCached.set(0);
        cumulativeReasoning.set(0);
        cumulativeCompletion.set(0);
        cumulativeAllTokens.set(0);
        LlmClient.bindTelemetry(event -> {
            cumulativePrompt.addAndGet(event.promptTokens());
            cumulativeCached.addAndGet(event.cachedTokens());
            cumulativeReasoning.addAndGet(event.reasoningTokens());
            cumulativeCompletion.addAndGet(event.completionTokens());
            cumulativeAllTokens.addAndGet(event.totalTokens());
            emit.accept(Events.of(runId, "llm_call",
                    event.role() + " " + event.model() + " · "
                            + (event.errorMessage() == null
                                    ? ("p=" + event.promptTokens()
                                            + " cached=" + event.cachedTokens()
                                            + " reason=" + event.reasoningTokens()
                                            + " c=" + event.completionTokens()
                                            + " t=" + event.totalTokens()
                                            + " in " + event.elapsedMs() + "ms")
                                    : ("ERROR " + event.httpStatus() + " in " + event.elapsedMs() + "ms")),
                    Events.meta()
                            .put("callId", event.callId())
                            .put("role", event.role())
                            .put("model", event.model())
                            .put("lane", event.lane())
                            .put("promptTokens", event.promptTokens())
                            .put("cachedTokens", event.cachedTokens())
                            .put("reasoningTokens", event.reasoningTokens())
                            .put("completionTokens", event.completionTokens())
                            .put("totalTokens", event.totalTokens())
                            .put("requestBytes", event.requestBytes())
                            .put("responseBytes", event.responseBytes())
                            .put("elapsedMs", event.elapsedMs())
                            .put("httpStatus", event.httpStatus())
                            .put("finishReason", event.finishReason() == null ? "" : event.finishReason())
                            .put("errorMessage", event.errorMessage() == null ? "" : event.errorMessage())
                            .put("cumulativePromptTokens", cumulativePrompt.get())
                            .put("cumulativeCachedTokens", cumulativeCached.get())
                            .put("cumulativeReasoningTokens", cumulativeReasoning.get())
                            .put("cumulativeCompletionTokens", cumulativeCompletion.get())
                            .put("cumulativeTotalTokens", cumulativeAllTokens.get())
                            .build()));
        });
    }

    private Result runLoop(String runId, String userTask, Consumer<Events.Event> emit) {
        emit.accept(Events.of(runId, "task_created", "Agent received task",
                Map.of("prompt", userTask)));

        // Parse the user goal into a structured contract once at run start so
        // every downstream component (planner, verifier, completion check)
        // reads quantifiers, spatial relations, and entity refs from a single
        // canonical source instead of re-interpreting the prose.
        runContract = GoalContract.empty(userTask);
        if (goalContractExtractor != null && goalContractExtractor.enabled()) {
            try {
                runContract = goalContractExtractor.extract(userTask);
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) throw ex;
                log.warn("[goal_contract] extractor failed at run start: {}", ex.toString());
            }
        }
        if (!runContract.isEmpty()) {
            emit.accept(Events.of(runId, "goal_contract_extracted",
                    "Parsed user goal into " + runContract.items().size() + " contract item(s)",
                    Events.meta()
                            .put("itemCount", runContract.items().size())
                            .put("rendered", truncate(renderGoalContract(runContract), 1200))
                            .build()));
        }

        if ("seeact".equalsIgnoreCase(flow) && seeActPlanner != null) {
            boolean grounder = uGround != null && uGround.enabled();
            emit.accept(Events.of(runId, "flow_selected",
                    "Using seeact flow (" + seeActPlanner.model()
                            + (grounder ? " + " + uGround.model() : " only, UGround disabled")
                            + ")",
                    Map.of("flow", "seeact", "planner", seeActPlanner.model(),
                            "grounder", grounder ? uGround.model() : "disabled")));
            return tools.withRawScreenshots(() -> runSeeActLoop(runId, userTask, emit));
        }
        if ("pro-actor".equalsIgnoreCase(flow) && proActor != null) {
            emit.accept(Events.of(runId, "flow_selected",
                    "Using pro-actor flow (single " + proActor.model() + " call per turn)",
                    Map.of("flow", "pro-actor", "model", proActor.model())));
            return runProActorLoop(runId, userTask, emit);
        }
        emit.accept(Events.of(runId, "flow_selected", "Using legacy actor (XML <tools>) flow",
                Map.of("flow", "actor")));

        DoomGuard guard = new DoomGuard(Math.max(2, doomThreshold));
        List<HistoryTurn> history = new ArrayList<>();
        long cumulativeTokens = 0L;
        long cumulativePromptTokens = 0L;
        long cumulativeCompletionTokens = 0L;
        String runningSummary = "";

        String runPlan = "";
        if (planner != null) {
            try {
                bailIfCancelled();
                emit.accept(Events.of(runId, "planner_started",
                        "Planning task with " + planner.model() + " before iterating",
                        Map.of("model", planner.model())));
                byte[] initialShot = tools.invoke("screenshot", MAPPER.createObjectNode()).screenshotPng();
                runPlan = planner.plan(userTask, initialShot);
                if (runPlan != null && !runPlan.isBlank()) {
                    emit.accept(Events.of(runId, "planner_completed",
                            "Plan ready · " + runPlan.length() + " chars",
                            Events.meta()
                                    .put("model", planner.model())
                                    .put("chars", runPlan.length())
                                    .put("text", truncate(runPlan, 4000))
                                    .build()));
                } else {
                    emit.accept(Events.of(runId, "planner_failed",
                            "Planner returned empty text · proceeding without plan",
                            Map.of("model", planner.model())));
                }
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) throw ex;
                log.warn("[agent] planner failed: {}", ex.toString());
                emit.accept(Events.of(runId, "planner_failed",
                        "Planner error: " + ex.getMessage() + " · proceeding without plan",
                        Map.of()));
            }
        }

        for (int step = 1; step <= stepBudget; step++) {
            bailIfCancelled();
            emit.accept(Events.of(runId, "iteration_started", "Iteration " + step + "/" + stepBudget,
                    Map.of("iteration", String.valueOf(step))));

            List<LlmClient.Message> messages = buildMessages(userTask, history, runningSummary, runPlan);

            bailIfCancelled();
            emit.accept(Events.of(runId, "model_request", "Model request prepared",
                    Map.of("messageCount", String.valueOf(messages.size()))));
            LlmClient.Reply reply;
            try {
                reply = llm.chat(LlmClient.Lane.VISION, null, messages, 0.2, 32768);
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) throw ex;
                log.error("[agent] LLM call failed: {}", ex.toString());
                emit.accept(Events.of(runId, "model_failed", "LLM call failed: " + ex.getMessage(),
                        Map.of("error", ex.toString())));
                return Result.failure("llm_call_failed: " + ex.getMessage(), step);
            }
            String assistantText = XmlToolFormat.stripToolsBlock(reply.text());
            cumulativePromptTokens += reply.promptTokens();
            cumulativeCompletionTokens += reply.completionTokens();
            cumulativeTokens += reply.totalTokens();
            emit.accept(Events.of(runId, "model_response", "Model response received",
                    Events.meta()
                            .put("assistantText", assistantText == null ? "" : assistantText)
                            .put("finishReason", reply.finishReason() == null ? "" : reply.finishReason())
                            .put("promptTokens", reply.promptTokens())
                            .put("completionTokens", reply.completionTokens())
                            .put("totalTokens", reply.totalTokens())
                            .put("cumulativePromptTokens", cumulativePromptTokens)
                            .put("cumulativeCompletionTokens", cumulativeCompletionTokens)
                            .put("cumulativeTotalTokens", cumulativeTokens)
                            .build()));
            emit.accept(Events.of(runId, "token_usage",
                    "tokens step=" + reply.totalTokens() + " total=" + cumulativeTokens,
                    Events.meta()
                            .put("source", "agent_loop")
                            .put("model", llm.defaultModel(LlmClient.Lane.VISION))
                            .put("step", step)
                            .put("promptTokens", reply.promptTokens())
                            .put("completionTokens", reply.completionTokens())
                            .put("totalTokens", reply.totalTokens())
                            .put("cumulativePromptTokens", cumulativePromptTokens)
                            .put("cumulativeCompletionTokens", cumulativeCompletionTokens)
                            .put("cumulativeTotalTokens", cumulativeTokens)
                            .build()));

            List<XmlToolFormat.ParsedCall> parsed = XmlToolFormat.parseToolCalls(reply.text());
            List<XmlToolFormat.ParsedCall> deduped = dedupAndCap(parsed);
            if (deduped.isEmpty()) {
                log.warn("[agent] step={} no executable tool call", step);
                String feedback = noToolFeedback(assistantText);
                emit.accept(Events.of(runId, "tool_failed", feedback, Map.of()));
                history.add(formatFeedbackTurn(step, assistantText, feedback));
                runningSummary = compactHistoryIfNeeded(runId, step, userTask,
                        history, runningSummary, emit);
                continue;
            }

            List<Tools.Outcome> outcomes = new ArrayList<>();
            for (XmlToolFormat.ParsedCall pc : deduped) {
                bailIfCancelled();
                emit.accept(Events.of(runId, "tool_called", pc.name(),
                        Events.meta()
                                .put("name", pc.name())
                                .put("arguments", pc.argsJson())
                                .put("reason", pc.reason() == null ? "" : pc.reason())
                                .put("iteration", step)
                                .build()));

                String blocked = guard.check(pc.name(), pc.argsJson());
                if (blocked != null) {
                    emit.accept(Events.of(runId, "tool_blocked", "doom_loop",
                            Map.of("tool", pc.name(), "iteration", String.valueOf(step))));
                    emit.accept(Events.of(runId, "tool_failed", blocked, Map.of("tool", pc.name())));
                    outcomes.add(Tools.Outcome.fail(blocked));
                    continue;
                }

                JsonNode argsNode = parseArgs(pc.argsJson());
                argsNode = applyGrounding(runId, step, pc, argsNode, emit);
                Tools.Outcome out = tools.invoke(pc.name(), argsNode);
                if (out.done() && !hasSuccessfulWork(history, deduped, outcomes)) {
                    out = Tools.Outcome.fail("done rejected: no successful action ran before done. "
                            + "Call write_file/click/type/hotkey/etc first, then call done with a summary.");
                }
                if (out.done() && hasSuccessfulVisualWork(history, deduped, outcomes)) {
                    Verification verification = verifyVisualCompletion(runId, userTask, emit);
                    if (verification.isInconclusive()) {
                        emit.accept(Events.of(runId, "completion_inconclusive", verification.reason(),
                                Map.of("iteration", String.valueOf(step))));
                        // Verifier could not produce a parseable verdict. Do not invent
                        // a REJECT from that — the model's done call stands.
                    } else if (!verification.ok()) {
                        emit.accept(Events.of(runId, "completion_rejected", verification.reason(),
                                Map.of("iteration", String.valueOf(step))));
                        out = Tools.Outcome.fail("completion rejected by visual verifier: " + verification.reason()
                                + ". Continue from the screenshot and satisfy the original user request.");
                    } else {
                        emit.accept(Events.of(runId, "completion_verified", verification.reason(),
                                Map.of("iteration", String.valueOf(step))));
                    }
                }
                outcomes.add(out);
                emit.accept(Events.of(runId, out.ok() ? "tool_completed" : "tool_failed", out.summary(),
                        Map.of("name", pc.name(), "iteration", String.valueOf(step))));
                if (out.metadata().containsKey("intent_matched")) {
                    Map<String, String> m = out.metadata();
                    String matched = m.getOrDefault("intent_matched", "");
                    String action = m.getOrDefault("intent_action", "click");
                    String cx = m.getOrDefault("intent_center_x", "?");
                    String cy = m.getOrDefault("intent_center_y", "?");
                    boolean intentOk = "true".equals(m.get("intent_ok"));
                    String summary = intentOk ? action + " '" + matched + "' at (" + cx + ", " + cy + ") via OCR"
                            : "intent_failed: " + m.getOrDefault("intent_reason", "no_match");
                    java.util.Map<String, String> intentMeta = new java.util.LinkedHashMap<>(m);
                    intentMeta.put("name", pc.name());
                    intentMeta.put("iteration", String.valueOf(step));
                    emit.accept(Events.of(runId, "intent_applied", summary, intentMeta));
                }
                if (out.screenshotPng() != null && out.screenshotPng().length > 0) {
                    emit.accept(Events.of(runId, "screenshot_taken",
                            "Frame after " + pc.name() + " · " + out.screenshotPng().length + " bytes",
                            Map.of("bytes", String.valueOf(out.screenshotPng().length))));
                }
                if (out.done()) {
                    emit.accept(Events.of(runId, "task_completed", out.summary(),
                            Map.of("steps", String.valueOf(step))));
                    return Result.success(out.summary(), step);
                }
            }

            history.add(new HistoryTurn(step, assistantText, deduped, outcomes));
            runningSummary = compactHistoryIfNeeded(runId, step, userTask,
                    history, runningSummary, emit);
        }

        emit.accept(Events.of(runId, "task_failed",
                "Step budget exhausted: " + stepBudget,
                Map.of("steps", String.valueOf(stepBudget))));
        return Result.failure("budget_exhausted", stepBudget);
    }

    public Result runSubTask(String runId, String goal, String completionCriteria,
            int maxSteps, Consumer<Events.Event> emit) {
        int budget = Math.min(80, Math.max(1, maxSteps));
        emit.accept(Events.of(runId, "subtask_created", "Subtask agent received task",
                Events.meta()
                        .put("goal", goal == null ? "" : goal)
                        .put("completionCriteria", completionCriteria == null ? "" : completionCriteria)
                        .put("maxSteps", budget)
                        .build()));

        Agent sub = new Agent(llm, tools, grounding, planner, null, basePrompt, budget);
        sub.doomThreshold = doomThreshold;
        sub.groundingEnabled = groundingEnabled;
        sub.summarizerModel = summarizerModel;
        sub.summarizerMaxTokens = summarizerMaxTokens;
        sub.checkpointVerifierModel = checkpointVerifierModel;
        sub.checkpointVerifierMaxTokens = checkpointVerifierMaxTokens;
        sub.flow = flow;
        sub.proActor = proActor;
        sub.seeActPlanner = seeActPlanner;
        sub.uGround = uGround;
        sub.expertConsultant = expertConsultant;

        String task = "[subtask]\n" + (goal == null ? "" : goal)
                + "\n\n[completion criteria]\n" + (completionCriteria == null ? "" : completionCriteria);
        return sub.run(runId, task, event -> emit.accept(subtaskEvent(event)), currentCancelCheck());
    }

    private static Events.Event subtaskEvent(Events.Event event) {
        String type = switch (event.type()) {
            case "task_completed" -> "subtask_agent_done";
            case "task_failed" -> "subtask_agent_failed";
            case "task_cancelled" -> "subtask_agent_cancelled";
            default -> event.type();
        };
        Map<String, String> meta = new java.util.LinkedHashMap<>(event.metadata());
        if ("iteration_started".equals(event.type())) {
            meta.put("subtask", "true");
        }
        return new Events.Event(event.eventId(), event.runId(), type, event.message(), meta, event.at());
    }

    /** SeeAct-style loop: raw screenshot -> Gemini planner -> optional UGround
     *  pixel grounding -> Java tools. OmniParser is bypassed for screenshots and post-action
     *  observations in this flow. */
    private Result runSeeActLoop(String runId, String userTask, Consumer<Events.Event> emit) {
        DoomGuard guard = new DoomGuard(Math.max(2, doomThreshold));
        String progressNote = "";
        String lastObservation = "";
        // Supervisor state: consult every N planner calls. The pending tip is
        // the supervisor's last non-"continue" message; we prepend it to
        // lastObservation on the planner's next call and then clear it.
        int plannerCallCount = 0;
        String supervisorTip = null;
        java.util.ArrayDeque<String> recentTurns = new java.util.ArrayDeque<>();
        final int RECENT_TURN_CAP = 20;

        // Repeated-action diagnostic hint. When the same non-navigation
        // routeHash appears 2+ times within the last RECENT_SIG_WINDOW planner
        // turns (not just consecutively — interleaving with other actions
        // still counts), the planner is clearly cycling without progress.
        // Pixel-diff is unreliable on this sandbox (see hasNoVisibleChange) so
        // this behavioural signal is the deterministic substitute. The hint
        // is prepended to the planner's next observation and auto-clears the
        // moment the cycling signature drops out of the window.
        java.util.ArrayDeque<String> recentSigs = new java.util.ArrayDeque<>();
        final int RECENT_SIG_WINDOW = 5;
        String repeatedActionSig = null;
        boolean repeatedActionActive = false;
        // Scroll-only doom-loop detector. Navigation actions are exempt
        // from the recentSigs repetition check (legitimate work may
        // require multiple scrolls), but a long run of pure navigation
        // with no concrete commit in between is the verifier-stuck
        // pattern — agent keeps trying to bring evidence into view
        // instead of accepting the active checkpoint or pivoting.
        // Counter resets the instant a non-navigation action runs.
        int navOnlyStreak = 0;
        final int NAV_ONLY_STREAK_THRESHOLD = 4;
        boolean producedVisualWork = false;
        GroundingState groundingState = new GroundingState(uGround != null && uGround.enabled());
        // Wire UGround vote-mode disagreement → planner-visible event with
        // strong advisory wording. We don't hard-block — just make the
        // message loud enough that Lite actually pivots routes instead of
        // re-clicking the same ambiguous icon.
        if (uGround != null) {
            uGround.onDisagreement(ev -> {
                StringBuilder picksStr = new StringBuilder();
                for (int[] p : ev.picks()) {
                    if (picksStr.length() > 0) picksStr.append(' ');
                    picksStr.append('(').append(p[0]).append(',').append(p[1]).append(')');
                }
                String desc = ev.description() == null ? "" : ev.description();
                String advice = ev.spreadPx() >= 150
                        ? "Hey — heads up: this is the kind of place where pixel"
                        + " clicking is unreliable. Strongly consider a keyboard route"
                        + " instead: alt+v for the View menu, or ctrl+F8 for Custom"
                        + " Animation, or alt+i for Insert. Or sharpen the description"
                        + " (exact label text, color, neighbor element) before retrying."
                        : "Click is slightly uncertain — proceed but be ready to switch"
                        + " routes if the next screenshot doesn't show the expected change.";
                emit.accept(Events.of(runId, "grounding_disagreement",
                        "⚠️ UGround vote: 3 resolutions disagreed for '"
                                + truncate(desc, 80) + "' (spread "
                                + ev.spreadPx() + "px; picks " + picksStr + ") → "
                                + "median (" + ev.chosenX() + "," + ev.chosenY() + "). "
                                + advice,
                        Events.meta()
                                .put("description", desc)
                                .put("spreadPx", String.valueOf(ev.spreadPx()))
                                .put("chosenX", String.valueOf(ev.chosenX()))
                                .put("chosenY", String.valueOf(ev.chosenY()))
                                .put("picks", picksStr.toString())
                                .build()));
            });
        }
        RecursiveTodoState checklist = new RecursiveTodoState();
        CalmRepairState repair = new CalmRepairState();
        int rejectedDoneCount = 0;
        boolean checkpointVerificationPending = false;
        byte[] verificationProofShot = null;
        String lastCachedRejectCheckpointId = null;
        boolean observationSettlePending = false;
        // Tracks whether a JUSTIFIED destructive action (close_window, quit_app,
        // shell rm, etc.) executed during this run. Used to gate the expensive
        // BINARY_VERIFIER stale-checklist guard — if no destructive action ever
        // fired, the checklist [x] state cannot have been wiped, and the visual
        // recheck (~25K tokens) is pure waste.
        boolean destructiveActionFired = false;
        Map<String, Integer> evidenceNavigationMoves = new HashMap<>();

        // Waypoint plans live OUTSIDE the LLM call. When the planner emits
        // plan_waypoints for a checkpoint, the waypoint list is parked here
        // and walked one-per-iteration WITHOUT calling the planner LLM. The
        // plan persists across iterations as durable runtime state — fixes
        // the "random walk" failure where each LLM call decides anchor
        // placement from scratch with no memory of where the path started.
        // Keyed by checkpointId so each checkpoint can own its own plan.
        java.util.Map<String, WaypointPlan> activePlans = new java.util.HashMap<>();

        for (int step = 1; step <= stepBudget; step++) {
            bailIfCancelled();
            emit.accept(Events.of(runId, "iteration_started", "Iteration " + step + "/" + stepBudget,
                    Map.of("iteration", String.valueOf(step), "flow", "seeact")));
            if (observationSettlePending) {
                observationSettlePending = false;
                settleBeforeObservation(runId, step, emit);
            }

            byte[] rawPng;
            long shotStart = System.currentTimeMillis();
            try {
                rawPng = tools.captureRawScreenshot();
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) throw ex;
                String reason = "screenshot_failed: " + ex.getMessage();
                emit.accept(Events.of(runId, "screenshot_failed", reason,
                        Map.of("iteration", String.valueOf(step))));
                emit.accept(Events.of(runId, "task_failed", reason,
                        Map.of("steps", String.valueOf(step))));
                return Result.failure(reason, step);
            }
            long shotMs = System.currentTimeMillis() - shotStart;
            if (shotMs > 5000) {
                log.warn("[run] {} iter {} screenshot capture took {}ms (sandbox latency suspected)",
                        runId, step, shotMs);
                emit.accept(Events.of(runId, "slow_screenshot",
                        "Screenshot capture took " + shotMs + "ms",
                        Events.meta()
                                .put("iteration", step)
                                .put("elapsedMs", shotMs)
                                .build()));
            }
            // Store the screenshot in the evidence store for this task
            if (rawPng != null && rawPng.length > 0) {
                evidenceStore.putShot(taskSeqOf(checklist), activeCheckpointId(checklist), rawPng);
            }
            bailIfCancelled();
            emit.accept(Events.of(runId, "screenshot_taken",
                    "Raw screenshot · " + (rawPng == null ? 0 : rawPng.length) + " bytes",
                    Events.meta()
                            .put("iteration", step)
                            .put("bytes", rawPng == null ? 0 : rawPng.length)
                            .put("labeled", false)
                            .build()));

            if (checkpointVerificationPending && checklist.initialized()) {
                emit.accept(Events.of(runId, "checkpoint_check_started",
                        "Verifying active checkpoint: " + checklist.activeText(),
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", checklist.activeText())
                                .put("model", checkpointVerifierModel)
                                .build()));
                String judgedCheckpointId = activeCheckpointId(checklist);
                byte[] proofShot = verificationProofShot != null ? verificationProofShot : rawPng;
                verificationProofShot = null;
                String evidence = "";
                try {
                    evidence = tools.evidenceBundle(null, null);
                } catch (RuntimeException ex) {
                    log.warn("[verifier] evidence bundle fetch failed: {}", ex.toString());
                }
                if (evidence != null && !evidence.isBlank()) {
                    emit.accept(Events.of(runId, "verifier_evidence",
                            "Evidence bundle attached (sandbox windows + filesystem + clipboard)",
                            Events.meta()
                                    .put("iteration", step)
                                    .put("bytes", evidence.length())
                                    .build()));
                }
                int checkpointTaskSeq = taskSeqOf(checklist);
                long checkpointStartedTs = taskStartedTsBySeq.getOrDefault(checkpointTaskSeq, -1L);
                Verification checkpoint = verifyActiveCheckpoint(runId, emit, userTask,
                        checklist.activeText(), proofShot, evidence,
                        checkpointTaskSeq, checkpointStartedTs, checklist.activeLineage());
                checkpointVerificationPending = false;
                if (checkpoint.ok()) {
                    evidenceNavigationMoves.remove(judgedCheckpointId);
                    blameLedger.resolvePendingFor(judgedCheckpointId,
                            BlameLedger.Status.VERIFIER_ACCEPT, checkpoint.reason());
                    repair.clear();
                    if (checklist.markActiveDone("[verifier] " + checkpoint.reason())) {
                        lastObservation = "[checkpoint accepted] " + checkpoint.reason();
                        emit.accept(Events.of(runId, "task_checklist_updated",
                                checklist.summary(),
                                Events.meta()
                                        .put("iteration", step)
                                        .put("done", checklist.doneCount())
                                        .put("items", checklist.size())
                                        .put("source", "checkpoint_verifier")
                                        .put("reason", checkpoint.reason())
                                        .build()));
                        // Do NOT auto-subdivide the next active sibling here. The next
                        // planner turn sees the new active checkpoint and decides whether
                        // to verify, act, or subdivide. The runtime's job is to advance
                        // and re-prompt the planner — never to pre-emptively decompose
                        // a checkpoint that hasn't even been observed yet. The previous
                        // behaviour ("split active checkpoint before action") generated
                        // identical-content sibling chains for state-fact goals where
                        // every newly-active checkpoint was already true.
                    }
                    continue;
                }
                if (checkpoint.needsEvidence()) {
                    EvidenceNavResult nav = performEvidenceNavigation(runId, step,
                            judgedCheckpointId, checkpoint, evidenceNavigationMoves, emit);
                    lastObservation = nav.observation();
                    checkpointVerificationPending = nav.dispatched();
                    verificationProofShot = null;
                    observationSettlePending = nav.dispatched();
                    continue;
                }
                if (checkpoint.isInconclusive()) {
                    // Same principle as the completion verifier: when the verifier
                    // reply was unparseable / missing a boolean ok, do not fabricate
                    // a REJECT. Do not resolve blame, do not cascade the splitter.
                    // Surface the inconclusive verdict so the planner gets fresh
                    // evidence next turn and can re-ask or pick a different route.
                    String channelsCheckedI = checkpoint.evidenceUsed().isEmpty()
                            ? "none" : String.join(",", checkpoint.evidenceUsed());
                    lastObservation = "[checkpoint inconclusive] " + checkpoint.reason()
                            + " (channels checked: " + channelsCheckedI + ")";
                    emit.accept(Events.of(runId, "checkpoint_inconclusive",
                            lastObservation,
                            Events.meta()
                                    .put("iteration", step)
                                    .put("checkpoint", checklist.activeText())
                                    .put("reason", checkpoint.reason())
                                    .put("cached", checkpoint.fromCache())
                                    .build()));
                    continue;
                }
                evidenceNavigationMoves.remove(judgedCheckpointId);
                blameLedger.resolvePendingFor(judgedCheckpointId,
                        BlameLedger.Status.VERIFIER_REJECT, checkpoint.reason());
                String channelsChecked = checkpoint.evidenceUsed().isEmpty()
                        ? "none" : String.join(",", checkpoint.evidenceUsed());
                lastObservation = "[checkpoint rejected] " + checkpoint.reason()
                        + " (channels checked: " + channelsChecked + ")";
                emit.accept(Events.of(runId, "checkpoint_rejected",
                        lastObservation,
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", checklist.activeText())
                                .put("reason", checkpoint.reason())
                                .put("cached", checkpoint.fromCache())
                                .build()));
                if (judgedCheckpointId.equals(lastCachedRejectCheckpointId)) {
                    String splitObs = pushAutoSubtasks(runId, step, checklist, repair,
                            "[blame] verifier rejected twice without action; forcing subdivide",
                            lastObservation, false, userTask, emit);
                    if (splitObs == null) {
                        String reason = "blame ledger saturated: verifier reject cannot subdivide further on "
                                + checklist.activeText();
                        emit.accept(Events.of(runId, "task_failed", reason,
                                Map.of("steps", String.valueOf(step))));
                        return Result.failure(reason, step);
                    }
                    lastObservation = splitObs;
                    lastCachedRejectCheckpointId = null;
                    continue;
                }
                // First reject — do NOT auto-subdivide. Subdividing on a
                // legitimate reject (the world genuinely doesn't match the goal)
                // wastes iterations bookkeeping a tautological subtree while the
                // world stays unchanged. The right response to "VS Code is
                // running, you wanted it not running" is for the planner to
                // take an action, not for the runtime to invent more siblings.
                //
                // Surface the verifier's reject reason as lastObservation so
                // the planner reads it next turn and can switch from "verify"
                // to a real action. Mark this checkpoint as rejected once;
                // if the SAME checkpoint rejects a second time without an
                // intervening action, the existing block above (lines around
                // 698-713) treats that as a stuck state and forces subdivide.
                lastCachedRejectCheckpointId = judgedCheckpointId;
            }

            // === Waypoint plan walker (durable plan state) =====================
            // If the active checkpoint has a stored WaypointPlan, dispatch the
            // next waypoint via Tools.invoke directly — no planner LLM call.
            // Saves ~7K tokens per waypoint and, more importantly, prevents the
            // random-walk failure: each iteration's anchor decision now belongs
            // to a single coherent plan emitted once at checkpoint start.
            String walkerCpId = activeCheckpointId(checklist);
            WaypointPlan walkerPlan = walkerCpId == null ? null : activePlans.get(walkerCpId);
            if (walkerPlan != null && !walkerPlan.isDone()) {
                JsonNode wp = walkerPlan.current();
                String wpId = wp.path("id").asText("waypoint_" + walkerPlan.currentIndex);
                String wpDesc = wp.path("description").asText(wp.path("desc").asText(""));

                // Per-waypoint zoom lifecycle (designer flow):
                //   pre  = actions to run BEFORE grounding/clicking, e.g.
                //          zoom in to enlarge the target so UGround gets it
                //          at ±5 px instead of ±100 px on the overview.
                //   post = actions to run AFTER the click, e.g. zoom back out.
                // Each pre/post entry is dispatched via Tools.invoke directly,
                // same way batch subactions are handled.
                runWaypointSidecar(wp, "pre", wpId, runId, step, walkerCpId, emit);

                // Re-capture screenshot AFTER pre-actions so grounding sees the
                // zoomed view, not the stale overview.
                byte[] zoomedPng = rawPng;
                if (wp.path("pre").isArray() && wp.path("pre").size() > 0) {
                    try {
                        Tools.Outcome shot = tools.invoke("screenshot", MAPPER.createObjectNode());
                        if (shot != null && shot.screenshotPng() != null) zoomedPng = shot.screenshotPng();
                    } catch (Exception ex) {
                        log.warn("[waypoint] post-pre screenshot failed: {}", ex.getMessage());
                    }
                }

                Integer wpx = wp.has("x") && !wp.get("x").isNull() ? wp.get("x").asInt() : null;
                Integer wpy = wp.has("y") && !wp.get("y").isNull() ? wp.get("y").asInt() : null;
                if ((wpx == null || wpy == null) && !wpDesc.isBlank()
                        && uGround != null && uGround.enabled()) {
                    try {
                        UGroundClient.GroundedPoint gp = uGround.locate(zoomedPng, wpDesc);
                        wpx = gp.x(); wpy = gp.y();
                    } catch (Exception ex) {
                        log.warn("[waypoint] ground '{}' failed: {}", truncate(wpDesc, 80), ex.getMessage());
                    }
                }
                if (wpx == null || wpy == null) {
                    walkerPlan.advance();
                    emit.accept(Events.of(runId, "waypoint_skipped",
                            "[" + wpId + "] no coords and grounding failed",
                            Events.meta()
                                    .put("iteration", step)
                                    .put("waypointId", wpId)
                                    .put("checkpointId", walkerCpId)
                                    .build()));
                    observationSettlePending = true;
                    continue;
                }
                ObjectNode wpArgs = MAPPER.createObjectNode();
                wpArgs.put("x", wpx); wpArgs.put("y", wpy);
                // Per-waypoint `action` override; falls back to plan default.
                String dispatchAction = wp.path("action").asText("").trim();
                if (dispatchAction.isEmpty()) dispatchAction = walkerPlan.toolAction;
                emit.accept(Events.of(runId, "waypoint_dispatch",
                        "[" + (walkerPlan.currentIndex + 1) + "/" + walkerPlan.waypoints.size()
                                + "] " + dispatchAction + " " + wpId + " ("
                                + (wpDesc.isBlank() ? "no desc" : truncate(wpDesc, 60))
                                + ") at (" + wpx + "," + wpy + ")",
                        Events.meta()
                                .put("iteration", step)
                                .put("waypointId", wpId)
                                .put("index", walkerPlan.currentIndex)
                                .put("total", walkerPlan.waypoints.size())
                                .put("checkpointId", walkerCpId)
                                .put("action", dispatchAction)
                                .build()));
                Tools.Outcome wpOut;
                try { wpOut = tools.invoke(dispatchAction, wpArgs); }
                catch (Exception ex) { wpOut = null;
                    log.warn("[waypoint] dispatch '{}' threw: {}", dispatchAction, ex.getMessage()); }
                boolean ok = wpOut != null && wpOut.ok();
                if (!ok) {
                    walkerPlan.surpriseCount++;
                    if (walkerPlan.surpriseCount >= 2) {
                        activePlans.remove(walkerCpId);
                        lastObservation = "[waypoint] plan abandoned after 2 surprises at " + wpId
                                + "; falling back to standard planning";
                        emit.accept(Events.of(runId, "waypoint_plan_abandoned", lastObservation,
                                Events.meta()
                                        .put("iteration", step)
                                        .put("checkpointId", walkerCpId)
                                        .put("waypointId", wpId)
                                        .build()));
                        observationSettlePending = true;
                        continue;
                    }
                }
                // Per-waypoint POST actions (e.g. zoom back out). Run regardless
                // of whether the click succeeded, so we restore the view.
                runWaypointSidecar(wp, "post", wpId, runId, step, walkerCpId, emit);
                walkerPlan.advance();
                if (walkerPlan.isDone()) {
                    if (walkerPlan.closeWith != null) {
                        try {
                            tools.invoke("hotkey",
                                    MAPPER.createObjectNode().put("combo", walkerPlan.closeWith));
                            emit.accept(Events.of(runId, "waypoint_plan_closed",
                                    "Plan complete; closeWith hotkey '" + walkerPlan.closeWith + "' fired",
                                    Events.meta()
                                            .put("iteration", step)
                                            .put("checkpointId", walkerCpId)
                                            .put("closeWith", walkerPlan.closeWith)
                                            .build()));
                        } catch (Exception ex) {
                            log.warn("[waypoint] closeWith '{}' failed: {}", walkerPlan.closeWith, ex.getMessage());
                        }
                    }
                    activePlans.remove(walkerCpId);
                    lastObservation = "[waypoint] plan complete (" + walkerPlan.waypoints.size()
                            + " waypoints walked); next iteration verifies the checkpoint";
                } else {
                    lastObservation = "[waypoint] " + wpId + " dispatched at ("
                            + wpx + "," + wpy + "); " + walkerPlan.remaining() + " remaining";
                }
                observationSettlePending = true;
                continue;
            }
            // ===================================================================

            SeeActPlanner.Step planned;
            try {
                OmniParserClient.ParseResult parserContext = tools.parseRawScreenshot(rawPng);
                String parserContextText = renderParserContext(parserContext);
                if (parserContext != null) {
                    emit.accept(Events.of(runId, "parser_context_ready",
                            "OmniParser context · " + parserContext.elements().size()
                                    + " elements in " + parserContext.totalSeconds() + "s",
                            Events.meta()
                                    .put("iteration", step)
                                    .put("elements", parserContext.elements().size())
                                    .put("parseSeconds", String.valueOf(parserContext.totalSeconds()))
                                    .build()));
                }
                String activeId = activeCheckpointId(checklist);
                String invalidated = blameLedger.invalidatedSummary(activeId);
                String inventory = blameLedger.toolInventory(activeId);
                String blameBlock = invalidated.isBlank() ? inventory
                        : (inventory.isBlank() ? invalidated : invalidated + "\n" + inventory);
                // Compute current_state for the planner so it stops conflating
                // "I can't see X on this screen" with "X doesn't exist". The
                // verifier already had this channel; the planner did not, which
                // is why a GNOME Activities Overview screenshot (where windows
                // aren't drawn) made it claim VS Code wasn't running while
                // running_windows clearly listed it.
                String plannerCurrentState = "";
                try {
                    String evidenceJson = tools.evidenceBundle(null, null);
                    if (evidenceJson != null && !evidenceJson.isBlank()) {
                        plannerCurrentState = renderCurrentState(parseEvidenceJson(evidenceJson));
                    }
                } catch (RuntimeException ignored) {
                    // Channel down — planner falls back to screenshot-only, same as before.
                }
                String observationForPlanner = lastObservation;
                // Loop-detection signals (scroll-only streak, repeated-action
                // signature) are no longer injected into the planner's
                // observation as text hints — Flash-Lite ignored them. The
                // sole intervention path is the supervisor, which rewrites the
                // active checkpoint text (MODIFY), marks it done (ADVANCE), or
                // takes over the keyboard (EXECUTE). The planner sees only the
                // plan, not advisory text.
                if (bestOfN > 1 && bestOfNJudge != null) {
                    long t0 = System.currentTimeMillis();
                    List<SeeActPlanner.Step> candidates = seeActPlanner.nextCandidates(
                            bestOfN, bestOfNTemperature,
                            userTask, progressNote, observationForPlanner,
                            checklist.promptText(), checklist.acceptedText(),
                            blameBlock, plannerCurrentState,
                            renderGoalContract(runContract),
                            parserContextText,
                            rawPng, groundingState.coordinateFallbackAllowed());
                    BestOfNJudge.Verdict v = bestOfNJudge.pickBest(candidates, rawPng,
                            userTask, checklist.promptText());
                    planned = candidates.get(v.winnerIndex());
                    emit.accept(Events.of(runId, "best_of_n",
                            "picked " + (v.winnerIndex() + 1) + "/" + candidates.size()
                                    + " · " + truncate(v.reason(), 120),
                            Events.meta()
                                    .put("iteration", step)
                                    .put("n", candidates.size())
                                    .put("winner_index", v.winnerIndex())
                                    .put("judge_prompt_tokens", v.promptTokens())
                                    .put("judge_completion_tokens", v.completionTokens())
                                    .put("elapsed_ms", (int) (System.currentTimeMillis() - t0))
                                    .build()));
                } else {
                    planned = seeActPlanner.next(userTask, progressNote, observationForPlanner,
                            checklist.promptText(), checklist.acceptedText(),
                            blameBlock, plannerCurrentState,
                            renderGoalContract(runContract),
                            parserContextText,
                            rawPng, groundingState.coordinateFallbackAllowed());
                }
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) throw ex;
                log.error("[agent.seeact] planner failed: {}", ex.toString());
                emit.accept(Events.of(runId, "model_failed", "SeeAct planner error: " + ex.getMessage(),
                        Map.of("model", seeActPlanner.model())));
                return Result.failure("seeact_planner_failed: " + ex.getMessage(), step);
            }
            bailIfCancelled();

            if (planned.isParseError()) {
                String reason = "seeact_format_error: " + planned.parseError();
                emit.accept(Events.of(runId, "model_failed", reason,
                        Events.meta()
                                .put("iteration", step)
                                .put("model", seeActPlanner.model())
                                .put("error", planned.parseError())
                                .build()));
                emit.accept(Events.of(runId, "task_failed", reason,
                        Map.of("steps", String.valueOf(step))));
                return Result.failure(reason, step);
            }

            boolean hadChecklist = checklist.initialized();
            applyChecklist(runId, step, checklist, planned, emit);
            if (planned.observation() != null && !planned.observation().isBlank()) {
                emit.accept(Events.of(runId, "model_observation", planned.observation(),
                        Events.meta()
                                .put("iteration", step)
                                .put("source", "planner")
                                .build()));
            }

            plannerCallCount++;
            recentTurns.addLast("iter " + step
                    + " act=" + (planned.action() == null ? "?" : planned.action())
                    + (planned.combo() == null || planned.combo().isBlank() ? "" : " combo=" + planned.combo())
                    + (planned.hasCoords() ? " xy=" + planned.x() + "," + planned.y() : "")
                    + (planned.elementDescription() == null || planned.elementDescription().isBlank()
                            ? "" : " desc=\"" + truncate(planned.elementDescription(), 40) + "\"")
                    + (planned.observation() == null || planned.observation().isBlank()
                            ? "" : " obs=\"" + truncate(planned.observation(), 100) + "\""));
            while (recentTurns.size() > RECENT_TURN_CAP) recentTurns.removeFirst();

            // Repeated identical action → behavioural no-progress signal.
            // Navigation actions are exempt because legitimate task work often
            // involves repeated scrolling. The detector fires when the same
            // routeHash appears 2+ times within RECENT_SIG_WINDOW turns —
            // catches interleaved patterns like A,B,A,B,A that consecutive-
            // only detection would miss.
            String currentActionSig = ledgerTracked(planned.action()) ? routeHashFor(planned) : null;
            if (currentActionSig != null && !isNavigationAction(planned)) {
                recentSigs.addLast(currentActionSig);
                while (recentSigs.size() > RECENT_SIG_WINDOW) recentSigs.removeFirst();
                int matches = 0;
                for (String s : recentSigs) if (currentActionSig.equals(s)) matches++;
                if (matches >= 2) {
                    repeatedActionActive = true;
                    if (!currentActionSig.equals(repeatedActionSig)) {
                        repeatedActionSig = currentActionSig;
                        emit.accept(Events.of(runId, "repeated_action_detected",
                                "Repeated action detected (" + matches + " of last "
                                        + recentSigs.size() + ") — escalating to supervisor",
                                Events.meta()
                                        .put("iteration", step)
                                        .put("action", planned.action() == null ? "" : planned.action())
                                        .put("signature", currentActionSig)
                                        .put("matches", matches)
                                        .put("windowSize", recentSigs.size())
                                        .build()));
                    }
                } else if (repeatedActionActive
                        && (repeatedActionSig == null
                                || countMatches(recentSigs, repeatedActionSig) < 2)) {
                    repeatedActionActive = false;
                    repeatedActionSig = null;
                    emit.accept(Events.of(runId, "repeated_action_cleared",
                            "Repeating signature dropped out of window",
                            Map.of("iteration", String.valueOf(step))));
                }
            }
            boolean scrollLoopTripped = false;
            if (isNavigationAction(planned)) {
                navOnlyStreak++;
                // Re-trigger every NAV_ONLY_STREAK_THRESHOLD additional nav-only
                // turns (4, 8, 12, ...). One MODIFY from the supervisor is not
                // always enough — re-firing gives the supervisor (Pro) more
                // chances to escalate to ADVANCE or EXECUTE.
                if (navOnlyStreak >= NAV_ONLY_STREAK_THRESHOLD
                        && (navOnlyStreak - NAV_ONLY_STREAK_THRESHOLD)
                                % NAV_ONLY_STREAK_THRESHOLD == 0) {
                    scrollLoopTripped = true;
                    emit.accept(Events.of(runId, "scroll_loop_detected",
                            "Pure-navigation streak hit threshold — escalating to supervisor",
                            Events.meta()
                                    .put("iteration", step)
                                    .put("streak", navOnlyStreak)
                                    .put("threshold", NAV_ONLY_STREAK_THRESHOLD)
                                    .build()));
                }
            } else if (navOnlyStreak > 0) {
                navOnlyStreak = 0;
                emit.accept(Events.of(runId, "scroll_loop_cleared",
                        "Non-navigation action broke scroll streak",
                        Map.of("iteration", String.valueOf(step))));
            }
            if (supervisorAdvisor != null && supervisorAdvisor.enabled()
                    && plannerCallCount > 0
                    && (plannerCallCount % supervisorAdvisor.interval() == 0
                            || scrollLoopTripped
                            || repeatedActionActive)) {
                String activeText = checklist.activeText();
                boolean supervisorAngry = scrollLoopTripped || repeatedActionActive;
                SupervisorAdvisor.Verdict sv = supervisorAdvisor.consult(
                        activeText == null ? "(none)" : activeText,
                        java.util.List.copyOf(recentTurns),
                        supervisorAngry,
                        userTask,
                        supervisorAngry ? rawPng : null);
                if (sv.hasActions()) {
                    int executed = 0;
                    int succeeded = 0;
                    for (SupervisorAdvisor.ExecAction ea : sv.actions()) {
                        try {
                            Tools.Outcome out = tools.invoke(ea.name(), ea.argsNode());
                            executed++;
                            if (out != null && out.ok()) succeeded++;
                            emit.accept(Events.of(runId, "supervisor_exec",
                                    "[supervisor:exec " + ea.name() + "] "
                                            + truncate(out == null ? "" : out.summary(), 200),
                                    Events.meta()
                                            .put("iteration", step)
                                            .put("action", ea.name())
                                            .put("ok", out != null && out.ok())
                                            .put("index", executed)
                                            .put("reason", truncate(
                                                    ea.reason() == null ? "" : ea.reason(), 160))
                                            .build()));
                        } catch (RuntimeException ex) {
                            log.warn("[supervisor:exec] {} failed: {}", ea.name(), ex.toString());
                            emit.accept(Events.of(runId, "supervisor_exec",
                                    "[supervisor:exec " + ea.name() + "] FAILED: "
                                            + truncate(ex.toString(), 160),
                                    Events.meta()
                                            .put("iteration", step)
                                            .put("action", ea.name())
                                            .put("ok", false)
                                            .put("index", executed + 1)
                                            .build()));
                            executed++;
                        }
                    }
                    emit.accept(Events.of(runId, "supervisor_observation",
                            "[supervisor:execute] " + succeeded + "/" + executed + " actions ok · "
                                    + truncate(sv.reason() == null ? "" : sv.reason(), 160),
                            Events.meta()
                                    .put("iteration", step)
                                    .put("verdict", "execute")
                                    .put("angry", supervisorAngry)
                                    .put("executed", executed)
                                    .put("succeeded", succeeded)
                                    .put("plannerCallCount", plannerCallCount)
                                    .put("interval", supervisorAdvisor.interval())
                                    .build()));
                    // Reset all loop-detection state — supervisor just took
                    // concrete action; the next planner turn starts clean.
                    navOnlyStreak = 0;
                    repeatedActionActive = false;
                    repeatedActionSig = null;
                    recentSigs.clear();
                    // Skip the rest of this iteration — the planner's stuck
                    // action does NOT get dispatched. Loop continues at next
                    // iteration with a fresh screenshot.
                    continue;
                }
                if (sv.isAdvance()) {
                    boolean advanced = checklist.markActiveDone(
                            "[supervisor:advance] " + truncate(
                                    sv.reason() == null ? "active checkpoint already satisfied"
                                            : sv.reason(), 160));
                    emit.accept(Events.of(runId, "supervisor_observation",
                            "[supervisor:advance] " + truncate(
                                    sv.reason() == null ? "active checkpoint advanced"
                                            : sv.reason(), 200),
                            Events.meta()
                                    .put("iteration", step)
                                    .put("verdict", "advance")
                                    .put("angry", supervisorAngry)
                                    .put("applied", advanced)
                                    .put("plannerCallCount", plannerCallCount)
                                    .put("interval", supervisorAdvisor.interval())
                                    .build()));
                    if (advanced) {
                        // Reset loop-detection state so the new checkpoint starts clean.
                        navOnlyStreak = 0;
                        repeatedActionActive = false;
                        repeatedActionSig = null;
                        recentSigs.clear();
                        emit.accept(Events.of(runId, "task_checklist_updated",
                                checklist.summary(),
                                Events.meta()
                                        .put("iteration", step)
                                        .put("source", "supervisor")
                                        .put("reason", "[supervisor:advance] " + truncate(
                                                sv.reason() == null ? "active checkpoint complete"
                                                        : sv.reason(), 160))
                                        .build()));
                    }
                } else if (sv.hasEdit()) {
                    // Surgical plan rewrite: the supervisor's new_plan_text
                    // becomes the active checkpoint's text. The planner reads
                    // only the plan on the next turn, so the new wording is
                    // what steers behaviour — no advisory prepend.
                    String before = checklist.activeText() == null ? "" : checklist.activeText();
                    boolean applied = checklist.updateActiveText(sv.newPlanText());
                    emit.accept(Events.of(runId, "supervisor_observation",
                            "[supervisor:modify] " + truncate(sv.newPlanText(), 200),
                            Events.meta()
                                    .put("iteration", step)
                                    .put("verdict", "modify")
                                    .put("angry", supervisorAngry)
                                    .put("applied", applied)
                                    .put("before", truncate(before, 200))
                                    .put("after", truncate(sv.newPlanText(), 200))
                                    .put("reason", truncate(sv.reason() == null ? "" : sv.reason(), 200))
                                    .put("plannerCallCount", plannerCallCount)
                                    .put("interval", supervisorAdvisor.interval())
                                    .build()));
                    if (applied) {
                        emit.accept(Events.of(runId, "task_checklist_updated",
                                checklist.summary(),
                                Events.meta()
                                        .put("iteration", step)
                                        .put("source", "supervisor")
                                        .put("reason", "[supervisor] " + truncate(
                                                sv.reason() == null ? "plan rewrite" : sv.reason(), 160))
                                        .build()));
                    }
                } else {
                    emit.accept(Events.of(runId, "supervisor_observation",
                            "[supervisor:continue] no intervention",
                            Events.meta()
                                    .put("iteration", step)
                                    .put("verdict", "continue")
                                    .put("plannerCallCount", plannerCallCount)
                                    .build()));
                }
            }

            emit.accept(Events.of(runId, "step_planned",
                    planned.action()
                            + (planned.elementDescription() == null ? "" : " · " + truncate(planned.elementDescription(), 80))
                            + (planned.hasCoords() ? " · xy=" + planned.x() + "," + planned.y() : "")
                            + (planned.reason() == null || planned.reason().isBlank() ? "" : " · because " + truncate(planned.reason(), 120))
                            + (planned.goalTrace() == null ? "" : " · " + truncate(planned.goalTrace(), 120))
                            + (planned.goalTrace() != null || planned.goalLink() == null ? "" : " · " + truncate(planned.goalLink(), 40)),
                    Events.meta()
                            .put("iteration", step)
                            .put("action", planned.action())
                            .put("elementDescription", planned.elementDescription() == null ? "" : planned.elementDescription())
                            .put("x", planned.x() == null ? "" : planned.x().toString())
                            .put("y", planned.y() == null ? "" : planned.y().toString())
                            .put("text", planned.textToType() == null ? "" : planned.textToType())
                            .put("combo", planned.combo() == null ? "" : planned.combo())
                            .put("reason", planned.reason() == null ? "" : planned.reason())
                            .put("checkpointId", planned.checkpointId() == null ? "" : planned.checkpointId())
                            .put("observation", planned.observation() == null ? "" : planned.observation())
                            .put("goalLink", planned.goalLink() == null ? "" : planned.goalLink())
                            .put("goalTrace", planned.goalTrace() == null ? "" : planned.goalTrace())
                            .put("assumption", planned.assumption() == null ? "" : planned.assumption())
                            .put("verifiedBy", planned.verifiedBy() == null ? "" : planned.verifiedBy())
                            .build()));

            // === plan_waypoints handler =======================================
            // Planner emitted a full waypoint list for the active checkpoint.
            // Store it in activePlans; subsequent iterations walk it without
            // any further LLM call until exhausted.
            if ("plan_waypoints".equals(planned.action())) {
                JsonNode wps = planned.batchActions();  // parser stowed waypoints[] here
                if (wps == null || !wps.isArray() || wps.size() == 0) {
                    lastObservation = "[plan_waypoints] empty waypoints array; the planner must include "
                            + "a non-empty waypoints[{id,description,...}] list";
                    emit.accept(Events.of(runId, "waypoint_plan_rejected", lastObservation,
                            Events.meta().put("iteration", step).build()));
                    continue;
                }
                String planCpId = planned.checkpointId() == null || planned.checkpointId().isBlank()
                        ? activeCheckpointId(checklist) : planned.checkpointId();
                if (planCpId == null) {
                    lastObservation = "[plan_waypoints] no active checkpoint to attach plan to";
                    emit.accept(Events.of(runId, "waypoint_plan_rejected", lastObservation,
                            Events.meta().put("iteration", step).build()));
                    continue;
                }
                String toolAction = planned.target() == null || planned.target().isBlank()
                        ? "click" : planned.target();
                String closeWith = planned.combo();
                java.util.List<JsonNode> wpList = new ArrayList<>();
                wps.forEach(wpList::add);
                WaypointPlan plan = new WaypointPlan(planCpId, wpList, toolAction, closeWith);
                activePlans.put(planCpId, plan);
                emit.accept(Events.of(runId, "waypoint_plan_created",
                        "Stored waypoint plan: " + wpList.size() + " waypoints, toolAction="
                                + toolAction + (closeWith == null ? "" : ", closeWith=" + closeWith)
                                + " — runtime walks them without further planner LLM calls",
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpointId", planCpId)
                                .put("count", wpList.size())
                                .put("toolAction", toolAction)
                                .put("closeWith", closeWith == null ? "" : closeWith)
                                .build()));
                lastObservation = "[plan_waypoints] stored " + wpList.size() + " waypoints for "
                        + planCpId + "; walker will dispatch them on next iterations";
                observationSettlePending = false;
                continue;
            }
            // ===================================================================

            if (!checklist.initialized()) {
                // The planner can correctly observe at iter 1 that the goal state
                // is already true (e.g. "close chrome" while Chrome is already not
                // running) and emit `done`. The old guard refused to advance
                // without a checklist and the planner re-emitted `done` forever —
                // burning iterations until the step budget. Trust the planner's
                // `done` only after the completion verifier confirms it against
                // OS-level channels (current_state, windows_diff). If the
                // verifier rejects or is inconclusive, force the planner to
                // create a checklist with concrete acceptance criteria.
                if (planned.isTerminal() && "done".equals(planned.action())) {
                    Verification v = verifyVisualCompletion(runId, userTask, emit);
                    if (v.ok()) {
                        emit.accept(Events.of(runId, "completion_already_satisfied", v.reason(),
                                Map.of("iteration", String.valueOf(step))));
                        String summary = planned.summary() == null
                                ? "task already satisfied at start" : planned.summary();
                        emit.accept(Events.of(runId, "task_completed", summary,
                                Map.of("steps", String.valueOf(step))));
                        return Result.success(summary, step);
                    }
                    String evt = v.isInconclusive() ? "completion_inconclusive" : "completion_rejected";
                    emit.accept(Events.of(runId, evt, v.reason(),
                            Map.of("iteration", String.valueOf(step))));
                    lastObservation = "[planner-done not confirmed] " + v.reason()
                            + "; create a checklist with concrete acceptance states the verifier can check";
                } else {
                    lastObservation = "[checklist] missing; create a short checklist before acting";
                }
                emit.accept(Events.of(runId, "step_skipped", lastObservation,
                        Map.of("iteration", String.valueOf(step))));
                continue;
            }
            if (!hadChecklist && checklist.initialized()) {
                String splitObservation = pushAutoSubtasks(runId, step, checklist, repair,
                        "[checklist created] split compound active checkpoint",
                        planned.observation(), false, userTask, emit);
                lastObservation = splitObservation == null
                        ? "[checklist] created; next action must serve active checkpoint " + checklist.activeText()
                        : splitObservation;
                if (splitObservation == null) {
                    emit.accept(Events.of(runId, "step_skipped", lastObservation,
                            Map.of("iteration", String.valueOf(step))));
                }
                continue;
            }
            if (!planned.isTerminal() && !isRecoveryAction(planned)
                    && !checklist.acceptsPlan(planned.checkpointId())) {
                lastObservation = "[flow] rejected action outside active checkpoint " + checklist.activeText();
                emit.accept(Events.of(runId, "calm_action_blocked", lastObservation,
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", checklist.activeText())
                                .put("checkpointId", planned.checkpointId() == null ? "" : planned.checkpointId())
                                .build()));
                continue;
            }
            if ("skip_checkpoint".equals(planned.action())) {
                // Planner-side escape valve when the active TODO names a transient
                // prerequisite that the world has already moved past (e.g. checklist
                // says "startup wizard visible" but Impress opened on a blank slide
                // and the wizard never appeared). Treats the checkpoint as
                // ACCEPTED-BY-OBSOLESCENCE so the loop advances; the verifier can
                // also reach this state via {"ok":true,"obsolete":true}.
                String reason = planned.reason() == null || planned.reason().isBlank()
                        ? "planner observed world past this prerequisite"
                        : planned.reason();
                String activeText = checklist.activeText();
                emit.accept(Events.of(runId, "checkpoint_skipped",
                        "skip_checkpoint: " + reason,
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", activeText == null ? "(none)" : activeText)
                                .put("status", "OBSOLETE")
                                .put("source", "planner")
                                .build()));
                if (checklist.markActiveDone("[planner:skip] " + reason)) {
                    lastObservation = "[skip_checkpoint] marked obsolete: " + reason
                            + " — moving to next checkpoint.";
                } else {
                    lastObservation = "[skip_checkpoint] no active checkpoint to skip.";
                }
                continue;
            }
            if ("subdivide_checkpoint".equals(planned.action())) {
                // Architectural rule: planner only signals "this is too broad".
                // Subtask CONTENT comes from the dedicated atomic CheckpointSplitter
                // call so the planner can never silently emit empty/partial subtasks.
                String reason = planned.reason() == null || planned.reason().isBlank()
                        ? "planner asked to subdivide" : planned.reason();
                String observation = planned.observation();
                String splitObservation = pushAutoSubtasks(runId, step, checklist, repair,
                        reason, observation, false, userTask, emit);
                if (splitObservation != null) {
                    lastObservation = splitObservation;
                } else {
                    lastObservation = "[todo] active checkpoint cannot be decomposed further; verify directly";
                    emit.accept(Events.of(runId, "step_skipped", lastObservation,
                            Events.meta()
                                    .put("iteration", step)
                                    .put("checkpoint", checklist.activeText())
                                    .put("depth", checklist.depth())
                                    .build()));
                }
                continue;
            }
            if ("ground_check".equals(planned.action())) {
                // Read-only optional confirmation against the stronger UGround model.
                // The planner uses this when several plausible targets are visible
                // (multiple "Close" buttons, ambiguous icons) or when a previous click
                // landed at coordinates that didn't change the screen. UGround is
                // queried once; the result becomes lastObservation so the next planner
                // turn can act on it. Does not click, does not change state.
                String target = planned.target() == null ? "" : planned.target().trim();
                if (target.isEmpty()) {
                    lastObservation = "[ground_check] missing element_description; supply a specific visible target to confirm";
                    emit.accept(Events.of(runId, "ground_check_skipped", lastObservation,
                            Map.of("iteration", String.valueOf(step))));
                    continue;
                }
                if (uGround == null || !uGround.enabled()) {
                    lastObservation = "[ground_check] UGround unavailable; cannot confirm \"" + truncate(target, 80)
                            + "\". Pick a different route or proceed with a click action and read the screen-delta.";
                    emit.accept(Events.of(runId, "ground_check_skipped", lastObservation,
                            Map.of("iteration", String.valueOf(step))));
                    continue;
                }
                try {
                    UGroundClient.GroundedPoint point = uGround.locate(rawPng, target);
                    if (point == null || point.x() < 0 || point.y() < 0) {
                        lastObservation = "[ground_check] UGround did NOT find \"" + truncate(target, 80)
                                + "\" on the current screen. The target is not visible — pick a different route or scroll/switch view.";
                    } else {
                        lastObservation = "[ground_check] UGround found \"" + truncate(target, 80)
                                + "\" at (" + point.x() + "," + point.y() + ")."
                                + " To act on it, emit click/right_click/double_click with this same element_description"
                                + " — runtime will re-ground and click. Do NOT just re-emit ground_check.";
                    }
                    emit.accept(Events.of(runId, "ground_check_completed", lastObservation,
                            Events.meta()
                                    .put("iteration", step)
                                    .put("target", target)
                                    .put("x", point == null ? "" : String.valueOf(point.x()))
                                    .put("y", point == null ? "" : String.valueOf(point.y()))
                                    .build()));
                } catch (RuntimeException ex) {
                    if (ex.getCause() instanceof InterruptedException) throw ex;
                    lastObservation = "[ground_check] UGround error: " + truncate(ex.getMessage() == null ? ex.toString() : ex.getMessage(), 120)
                            + ". Treat target visibility as unknown.";
                    emit.accept(Events.of(runId, "ground_check_failed", lastObservation,
                            Map.of("iteration", String.valueOf(step), "target", target)));
                }
                continue;
            }
            if ("verify_checkpoint".equals(planned.action())) {
                // Guard: when the checklist is fully done there is no active checkpoint to
                // verify. Without this, the planner would emit verify_checkpoint into the
                // void, the runtime would queue verification with an empty target, the
                // verifier would key on "" + screenshot, and the cache would happily return
                // (or store) verdicts for an empty checkpoint — producing the iter-20-to-29
                // spin observed in the trace. Treat this as the goal state already being
                // satisfied: surface task_completed via the same checklist-completion path.
                String activeText = checklist.activeText();
                if (checklist.allDone() || activeText == null || activeText.isBlank()) {
                    log.warn("[run] {} iter {} planner emitted verify_checkpoint with no active checkpoint "
                            + "(allDone={}, activeText={}); cross-checking visual completion before exit",
                            runId, step, checklist.allDone(), activeText == null ? "null" : "blank");
                    // Stale-checklist guard: only fire when there's a real reason to suspect
                    // the checklist might lie. Two reasons exist:
                    //   (a) a destructive action ran during this run — close_window/Don't-Save
                    //       can leave [x] items whose visible state was wiped
                    //   (b) the active checkpoint is missing/blank — planner anomaly, can't
                    //       trust checklist reasoning
                    // If neither is true (clean run, every checkpoint individually verified),
                    // trust the checklist and skip the ~25K-token BINARY_VERIFIER call.
                    boolean needVisualRecheck = destructiveActionFired
                            || activeText == null || activeText.isBlank();
                    if (!needVisualRecheck) {
                        emit.accept(Events.of(runId, "completion_via_checklist",
                                "All checklist items verifier-accepted; clean run (no destructive actions) — skipping BINARY_VERIFIER",
                                Events.meta()
                                        .put("iteration", step)
                                        .put("items", checklist.size())
                                        .put("done", checklist.doneCount())
                                        .put("skippedBinaryVerifier", true)
                                        .build()));
                        String summary = "task complete (checklist clean)";
                        emit.accept(Events.of(runId, "task_completed", summary,
                                Map.of("steps", String.valueOf(step))));
                        return Result.success(summary, step);
                    }
                    Verification finalCheck = verifyVisualCompletion(runId, userTask, emit);
                    if (finalCheck.ok()) {
                        emit.accept(Events.of(runId, "completion_via_checklist",
                                "all checkpoints accepted AND visual verifier confirmed: " + finalCheck.reason(),
                                Events.meta()
                                        .put("iteration", step)
                                        .put("items", checklist.size())
                                        .put("done", checklist.doneCount())
                                        .build()));
                        String summary = "task complete (visual+checklist confirmed)";
                        emit.accept(Events.of(runId, "task_completed", summary,
                                Map.of("steps", String.valueOf(step))));
                        return Result.success(summary, step);
                    }
                    // Visual rejected: the world has been mutated since the checklist
                    // accepted those items. Reset and force re-work.
                    int resetCount = checklist.resetAllToPending(
                            "[stale-checklist] visual verifier rejected: " + finalCheck.reason());
                    emit.accept(Events.of(runId, "stale_checklist_reset",
                            "Visual completion rejected after checklist showed all-done; reset "
                                    + resetCount + " items so the planner can rebuild lost state. Verifier reason: "
                                    + finalCheck.reason(),
                            Events.meta()
                                    .put("iteration", step)
                                    .put("items", checklist.size())
                                    .put("resetCount", resetCount)
                                    .put("verifierReason", finalCheck.reason())
                                    .build()));
                    lastObservation = "[stale-checklist] checklist showed [x] but the screen does not. "
                            + finalCheck.reason() + " — rebuild what was lost.";
                    continue;
                }
                checkpointVerificationPending = true;
                verificationProofShot = rawPng;
                lastObservation = "[checkpoint] verifier queued for " + activeText;
                emit.accept(Events.of(runId, "checkpoint_check_queued", lastObservation,
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", activeText)
                                .put("checkpointId", planned.checkpointId() == null ? "" : planned.checkpointId())
                                .build()));
                continue;
            }

            String plannedCheckpointId = activeCheckpointId(checklist);
            int pendingBlameRowId = -1;

            if (planned.isTerminal()) {
                if ("done".equals(planned.action())) {
                    if (!checklist.allDone()) {
                        rejectedDoneCount++;
                        lastObservation = "[checklist] incomplete: " + checklist.incompleteSummary();
                        emit.accept(Events.of(runId, "completion_rejected", lastObservation,
                                Events.meta()
                                        .put("iteration", step)
                                        .put("rejectedDoneCount", rejectedDoneCount)
                                        .build()));
                        if (rejectedDoneCount >= 3) {
                            String reason = "repair exhausted: model repeated done while checklist stayed incomplete";
                            emit.accept(Events.of(runId, "task_failed", reason,
                                    Map.of("steps", String.valueOf(step))));
                            return Result.failure(reason, step);
                        }
                        continue;
                    }
                    // Completion proof: either (a) the agent produced visible work AND the
                    // final-state verifier accepts the screenshot, or (b) every checklist item
                    // has already been accepted by the per-checkpoint verifier. Path (b) covers
                    // the legitimate "goal already satisfied at task start" case — the user
                    // asked for a state, the state is true, no action is needed. Forcing an
                    // action in that case made the agent un-do its work to satisfy the guard
                    // (open LibreOffice just to close it again). Verifier acceptance per
                    // checkpoint is itself proof of completion; we accept directly without
                    // a redundant final-screenshot LLM call.
                    if (!producedVisualWork) {
                        emit.accept(Events.of(runId, "completion_via_checklist",
                                "All checklist items verifier-accepted; no actions were required to satisfy the goal",
                                Events.meta()
                                        .put("iteration", step)
                                        .put("items", checklist.size())
                                        .put("done", checklist.doneCount())
                                        .build()));
                        String summary = planned.summary() == null
                                ? "task already satisfied at start" : planned.summary();
                        emit.accept(Events.of(runId, "task_completed", summary,
                                Map.of("steps", String.valueOf(step))));
                        return Result.success(summary, step);
                    }
                    Verification verification = verifyVisualCompletion(runId, userTask, emit);
                    if (verification.isInconclusive() && checklist.allDone()) {
                        emit.accept(Events.of(runId, "completion_via_checklist",
                                "Completion verifier inconclusive; every checklist item is verifier-accepted, admitting on checklist evidence",
                                Events.meta()
                                        .put("iteration", step)
                                        .put("items", checklist.size())
                                        .put("done", checklist.doneCount())
                                        .put("verifierReason", verification.reason())
                                        .build()));
                        String summary = planned.summary() == null ? "task completed" : planned.summary();
                        emit.accept(Events.of(runId, "task_completed", summary,
                                Map.of("steps", String.valueOf(step))));
                        return Result.success(summary, step);
                    }
                    if (verification.isInconclusive()) {
                        emit.accept(Events.of(runId, "completion_inconclusive", verification.reason(),
                                Map.of("iteration", String.valueOf(step))));
                        lastObservation = "[verifier-inconclusive] " + verification.reason();
                        continue;
                    }
                    if (!verification.ok()) {
                        emit.accept(Events.of(runId, "completion_rejected", verification.reason(),
                                Map.of("iteration", String.valueOf(step))));
                        lastObservation = "[verifier] " + verification.reason();
                        continue;
                    }
                    emit.accept(Events.of(runId, "completion_verified", verification.reason(),
                            Map.of("iteration", String.valueOf(step))));
                    String summary = planned.summary() == null ? "task completed" : planned.summary();
                    emit.accept(Events.of(runId, "task_completed", summary,
                            Map.of("steps", String.valueOf(step))));
                    return Result.success(summary, step);
                }
                String reason = planned.summary() == null ? "fail without reason" : planned.summary();
                // Fix-B: false-fail guard. Weaker planners (e.g. flash-lite)
                // sometimes emit `fail` when the active checkpoint is already
                // visibly satisfied (e.g. checkpoint "Impress is closed" with
                // Impress not running). Before accepting fail as the run's
                // final verdict, ask the structural verifier on the active
                // checkpoint. If the verifier says ok:true (or obsolete), we
                // override fail → verify_checkpoint and let the loop continue.
                String activeCp = checklist.activeText();
                if (activeCp != null && !activeCp.isBlank() && rawPng != null && rawPng.length > 0) {
                    Verification override = verifyActiveCheckpoint(runId, emit, userTask,
                            activeCp, rawPng, "");
                    if (override != null && override.ok()) {
                        emit.accept(Events.of(runId, "fail_overridden_by_verifier",
                                "[fail-guard] planner emitted fail but active checkpoint is satisfied: "
                                        + override.reason(),
                                Events.meta()
                                        .put("iteration", step)
                                        .put("checkpoint", activeCp)
                                        .put("status", override.status().name())
                                        .put("plannerReason", reason)
                                        .build()));
                        if (checklist.markActiveDone("[verifier:fail-override] " + override.reason())) {
                            lastObservation = "[fail-guard] active checkpoint is satisfied; advancing.";
                        } else {
                            lastObservation = "[fail-guard] verifier accepted but checklist did not advance.";
                        }
                        continue;
                    }
                }
                emit.accept(Events.of(runId, "task_failed", reason,
                        Map.of("steps", String.valueOf(step))));
                return Result.failure(reason, step);
            }
            rejectedDoneCount = 0;

            // Architectural destructive-action guard.
            // The planner can re-read a conversational user goal mid-run (e.g.
            // "...close it then open it again then create slides...") and revive
            // an already-completed destructive intent at the wrong time. Concretely:
            // an Impress task progressed to "Slide 2 created" and then the planner
            // emitted close_window because the original goal-text said "close it",
            // wiping unsaved progress. Guard: a destructive action is only allowed
            // when the ACTIVE checkpoint's text NAMES the destruction. If the
            // active checkpoint does not name it, skip the step and force the
            // planner to either (a) verify_checkpoint on the matching prior
            // checkpoint or (b) pick a non-destructive route.
            if (DESTRUCTIVE_ACTIONS.contains(planned.action())) {
                String activeText = checklist.activeText();
                boolean justified = destructiveActionJustified(activeText, userTask);
                if (!justified) {
                    String guardObs = "[destructive-guard] action '" + planned.action()
                            + "' (target=" + (planned.target() == null ? "?" : truncate(planned.target(), 60))
                            + ") is destructive but the active checkpoint is not a user-backed destructive state: \""
                            + truncate(activeText == null ? "(none)" : activeText, 100)
                            + "\". This often happens when the planner re-reads a conversational"
                            + " user goal and revives an already-completed destructive intent at"
                            + " the wrong time. Pick a NON-destructive route, or emit"
                            + " verify_checkpoint on the prior matching [x] item if it's already"
                            + " done.";
                    lastObservation = guardObs;
                    emit.accept(Events.of(runId, "destructive_action_blocked", guardObs,
                            Events.meta()
                                    .put("iteration", step)
                                    .put("action", planned.action())
                                    .put("activeCheckpoint", activeText == null ? "" : activeText)
                                    .build()));
                    continue;
                }
                // Justified destructive action about to fire — flag the run so
                // the stale-checklist guard knows to do a visual recheck later.
                destructiveActionFired = true;
            }

            // === Batched action plan (CodeAct-style) ============================
            // Planner emitted `actions: [...]` — execute each subaction in order
            // via the existing Tools dispatch, with no per-action grounding or
            // verifier round-trip. One screenshot at the end of the batch (via
            // the normal observation_settle on the next iteration). Saves
            // N × ~7K tokens for repetitive patterns (path tracing, multi-cell
            // entry, drag sequences).
            if (planned.isBatch()) {
                JsonNode batch = planned.batchActions();
                int total = batch.size();
                int dispatched = 0; int failed = 0; String lastError = null;
                // Pre-flight validation: scan ALL subactions before dispatching
                // any. If any is missing required target fields, fail the whole
                // batch up-front with a precise list. This stops us from burning
                // a planner LLM call to discover at dispatch time that
                // {"action":"click"} with no x/y/description is unactionable.
                List<String> validationIssues = validateBatchSubactions(batch);
                if (!validationIssues.isEmpty()) {
                    String summary = "[batch] schema rejected " + validationIssues.size() + " of " + total
                            + " subactions; nothing dispatched";
                    emit.accept(Events.of(runId, "batch_validation_failed",
                            summary + ":\n" + String.join("\n", validationIssues),
                            Events.meta()
                                    .put("iteration", step)
                                    .put("total", total)
                                    .put("issueCount", validationIssues.size())
                                    .put("checkpointId", planned.checkpointId() == null ? "" : planned.checkpointId())
                                    .build()));
                    lastObservation = summary + ". Each click sub-action MUST have either"
                            + " (x AND y) or element_description; type_text needs text and uses current focus; each drag MUST have either"
                            + " (from_x,y AND to_x,y) or (from_description AND to_description)."
                            + " Issues: " + String.join("; ", validationIssues);
                    observationSettlePending = false;
                    continue;
                }
                emit.accept(Events.of(runId, "batch_started",
                        "Executing " + total + " batched actions in one round-trip",
                        Events.meta()
                                .put("iteration", step)
                                .put("count", total)
                                .put("checkpointId", planned.checkpointId() == null ? "" : planned.checkpointId())
                                .build()));
                for (int bi = 0; bi < total; bi++) {
                    bailIfCancelled();
                    JsonNode sub = batch.get(bi);
                    String subAction = sub.path("action").asText("").trim();
                    if (subAction.isEmpty()) {
                        lastError = "[batch] subaction[" + bi + "] missing action field";
                        failed++; break;
                    }
                    String note = sub.path("note").asText(sub.path("comment").asText(""));
                    emit.accept(Events.of(runId, "batch_action",
                            "[" + (bi + 1) + "/" + total + "] " + subAction
                                    + (note.isBlank() ? "" : " — " + note),
                            Events.meta()
                                    .put("iteration", step)
                                    .put("index", bi)
                                    .put("action", subAction)
                                    .put("note", note)
                                    .build()));
                    // Ground element_description -> x,y when planner omitted
                    // raw coords. Saves the planner from having to read pixels;
                    // each grounding is one cheap UGround call (~1s) vs a full
                    // ~7K-token LLM round-trip.
                    JsonNode dispatchSub = groundBatchSubaction(sub, subAction, rawPng);
                    Tools.Outcome subOut;
                    try {
                        subOut = tools.invoke(subAction, dispatchSub);
                    } catch (Exception ex) {
                        lastError = "[batch] subaction[" + bi + "] '" + subAction
                                + "' threw: " + ex.getMessage();
                        failed++; break;
                    }
                    if (subOut == null || !subOut.ok()) {
                        lastError = "[batch] subaction[" + bi + "] '" + subAction + "' failed: "
                                + (subOut == null ? "null outcome" : truncate(subOut.summary(), 200));
                        failed++; break;
                    }
                    dispatched++;
                }
                emit.accept(Events.of(runId, "batch_completed",
                        "Batch finished: " + dispatched + "/" + total + " dispatched"
                                + (failed > 0 ? " — " + lastError : ""),
                        Events.meta()
                                .put("iteration", step)
                                .put("dispatched", dispatched)
                                .put("total", total)
                                .put("failed", failed)
                                .build()));
                lastObservation = failed > 0
                        ? "[batch] partial — " + lastError
                        : "[batch] dispatched " + dispatched + "/" + total
                                + " actions; verify the batch result on next screenshot";
                observationSettlePending = true;
                continue;
            }
            // ====================================================================

            List<ResolvedCall> resolved = resolveSeeActStep(planned, rawPng, groundingState, runId, step, emit, checklist);
            if (resolved.isEmpty()) {
                String groundingNote = groundingState.consumeNote();
                lastObservation = groundingNote.isBlank()
                        ? "[seeact] step skipped: no actionable tool resolved"
                        : groundingNote;
                continue;
            }

            String canonicalRouteHash = ledgerTracked(planned.action()) && !isNavigationAction(planned)
                    ? canonicalRouteHash(planned, resolved) : null;
            if (canonicalRouteHash != null) {
                blameLedger.invalidatePriorPending(plannedCheckpointId, canonicalRouteHash);
            }
            String blameSnapshot = blameLedger.invalidatedSummary(plannedCheckpointId);
            if (!blameSnapshot.isBlank()) {
                emit.accept(Events.of(runId, "blame_state", blameSnapshot,
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpointId", plannedCheckpointId)
                                .put("invalidCount", String.valueOf(
                                        blameLedger.invalidCount(plannedCheckpointId)))
                                .build()));
            }
            if (canonicalRouteHash != null
                    && blameLedger.isInvalidated(plannedCheckpointId, canonicalRouteHash)) {
                emit.accept(Events.of(runId, "route_invalidated",
                        "Planner re-emitted an already-invalidated route; descending to subdivide",
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", checklist.activeText())
                                .put("checkpointId", plannedCheckpointId)
                                .put("routeHash", canonicalRouteHash)
                                .put("description", describePlanned(planned))
                                .build()));
                String splitObservation = pushAutoSubtasks(runId, step, checklist, repair,
                        "[blame] route already invalidated; forcing subdivide",
                        lastObservation, false, userTask, emit);
                if (splitObservation == null) {
                    // Splitter says the active checkpoint is atomic. That is NOT a
                    // failure signal — it means "verify directly instead of splitting".
                    // Ask the verifier as the last-resort escalation. Only fail if the
                    // verifier itself rejects; visible_change=false alone is not proof.
                    Verification escalate = verifyActiveCheckpoint(runId, emit, userTask,
                            checklist.activeText(), rawPng, "");
                    if (escalate.ok()) {
                        blameLedger.resolvePendingFor(plannedCheckpointId,
                                BlameLedger.Status.VERIFIER_ACCEPT, escalate.reason());
                        repair.clear();
                        if (checklist.markActiveDone("[verifier:atomic-escalation] " + escalate.reason())) {
                            emit.accept(Events.of(runId, "task_checklist_updated",
                                    checklist.summary(),
                                    Events.meta()
                                            .put("iteration", step)
                                            .put("done", checklist.doneCount())
                                            .put("items", checklist.size())
                                            .put("source", "atomic_escalation")
                                            .put("reason", escalate.reason())
                                            .build()));
                        }
                        lastObservation = "[checkpoint accepted via atomic escalation] " + escalate.reason();
                        continue;
                    }
                    if (escalate.needsEvidence()) {
                        EvidenceNavResult nav = performEvidenceNavigation(runId, step,
                                plannedCheckpointId, escalate, evidenceNavigationMoves, emit);
                        lastObservation = nav.observation();
                        checkpointVerificationPending = nav.dispatched();
                        verificationProofShot = null;
                        observationSettlePending = nav.dispatched();
                        continue;
                    }
                    if (escalate.isInconclusive()) {
                        // Verifier could not decide; reset blame on this checkpoint so the
                        // planner gets one fresh round on a different route, rather than
                        // killing the run on a single-source heuristic (visible_change).
                        blameLedger.clearInvalidations(plannedCheckpointId);
                        lastObservation = "[atomic-escalation inconclusive; blame cleared, retry with a different route] "
                                + escalate.reason();
                        emit.accept(Events.of(runId, "atomic_escalation_inconclusive",
                                lastObservation,
                                Events.meta()
                                        .put("iteration", step)
                                        .put("checkpoint", checklist.activeText())
                                        .build()));
                        continue;
                    }
                    String reason = "blame ledger saturated: atomic checkpoint rejected by verifier on "
                            + checklist.activeText() + " — " + escalate.reason();
                    emit.accept(Events.of(runId, "task_failed", reason,
                            Map.of("steps", String.valueOf(step))));
                    return Result.failure(reason, step);
                }
                lastObservation = splitObservation;
                continue;
            }
            if (canonicalRouteHash != null) {
                pendingBlameRowId = blameLedger.recordPending(plannedCheckpointId, step,
                        describePlanned(planned), canonicalRouteHash);
            }

            CallSignature plannedSignature = CallSignature.from(resolved.get(0));
            if (repair.shouldBlock(plannedSignature)) {
                int blockedCount = repair.recordBlocked();
                lastObservation = repair.blockMessage(checklist.activeText(), plannedSignature);
                emit.accept(Events.of(runId, "calm_action_blocked", lastObservation,
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", checklist.activeText())
                                .put("blockedCount", blockedCount)
                                .build()));
                if (repair.exhausted()) {
                    if (checklist.canSubdivide() && !repair.subdivisionRequested()) {
                        repair.markSubdivisionRequested();
                        lastObservation = lastObservation
                                + " [recursive todo] Emit subdivide_checkpoint now with 2-3 smaller observable subtasks, or choose a different route.";
                        emit.accept(Events.of(runId, "calm_repair_continues", lastObservation,
                                Events.meta()
                                        .put("iteration", step)
                                        .put("checkpoint", checklist.activeText())
                                        .put("attempts", blockedCount)
                                        .put("depth", checklist.depth())
                                        .build()));
                        continue;
                    }
                    String reason = "repair exhausted: repeated same action without checkpoint progress";
                    emit.accept(Events.of(runId, "task_failed", reason,
                            Map.of("steps", String.valueOf(step))));
                    return Result.failure(reason, step);
                }
                continue;
            }

            List<String> summaries = new ArrayList<>();
            List<Tools.Outcome> outcomes = new ArrayList<>();
            boolean anyOk = false;
            for (ResolvedCall call : resolved) {
                String blocked = guard.check(call.toolName(), call.args().toString());
                if (blocked != null) {
                    emit.accept(Events.of(runId, "tool_blocked", "doom_loop",
                            Map.of("tool", call.toolName(), "iteration", String.valueOf(step))));
                    emit.accept(Events.of(runId, "tool_failed", blocked,
                            Map.of("tool", call.toolName())));
                    summaries.add("[doom_guard] " + blocked);
                    outcomes.add(Tools.Outcome.fail(blocked));
                    continue;
                }

                emit.accept(Events.of(runId, "tool_called", call.toolName(),
                        Events.meta()
                                .put("name", call.toolName())
                                .put("arguments", call.args().toString())
                                .put("reason", planned.reason() == null ? "" : planned.reason())
                                .put("iteration", step)
                                .build()));
                bailIfCancelled();
                Tools.Outcome out;
                try {
                    out = tools.invoke(call.toolName(), call.args());
                } catch (RuntimeException ex) {
                    if (ex.getCause() instanceof InterruptedException) throw ex;
                    out = Tools.Outcome.fail("tool threw: " + ex.getMessage());
                }
                bailIfCancelled();
                emit.accept(Events.of(runId, out.ok() ? "tool_completed" : "tool_failed", out.summary(),
                        toolMetadata(call.toolName(), step, out)));
                outcomes.add(out);
                if (out.ok()) recordNonPointerAction(checklist, call);
                if (out.ok() && !hasNoVisibleChange(out)) anyOk = true;
                if (out.ok() && countsAsVisualWork(call.toolName(), out)) producedVisualWork = true;
                summaries.add(observationSummary(call.toolName(), out));
            }
            if (shouldSettleBeforeNextObservation(resolved, outcomes)) {
                observationSettlePending = true;
            }
            if (pendingBlameRowId > 0) {
                BlameLedger.Status outcomeStatus = classifyOutcomeStatus(outcomes);
                // 2-strike dampening: a single transient zero-delta (window closing whose
                // repaint hasn't landed yet) must not poison the ledger. Require 2 consecutive
                // no_change strikes for the same route before recording NO_CHANGE.
                if (outcomeStatus == BlameLedger.Status.NO_CHANGE && canonicalRouteHash != null) {
                    int strikes = noChangeStrikes.merge(canonicalRouteHash, 1, Integer::sum);
                    if (strikes < 2) {
                        emit.accept(Events.of(runId, "no_change_pending",
                                "First no_change for this route; deferring ledger update until next strike",
                                Events.meta()
                                        .put("iteration", step)
                                        .put("strikes", strikes)
                                        .put("routeHash", canonicalRouteHash)
                                        .build()));
                        outcomeStatus = BlameLedger.Status.PENDING;
                    }
                } else if (canonicalRouteHash != null
                        && outcomeStatus != BlameLedger.Status.PENDING) {
                    // Any non-no_change resolution clears the strike count for this route.
                    noChangeStrikes.remove(canonicalRouteHash);
                }
                if (outcomeStatus != BlameLedger.Status.PENDING) {
                    blameLedger.update(pendingBlameRowId, outcomeStatus,
                            joinSummaries(summaries));
                }
            }
            lastCachedRejectCheckpointId = null;
            lastObservation = joinSummaries(summaries);
            if (checkpointProgressed(resolved, outcomes)) {
                repair.clear();
                evidenceNavigationMoves.remove(plannedCheckpointId);
                lastObservation = lastObservation
                        + " [checkpoint] If the active TODO is now visibly satisfied, emit verify_checkpoint; otherwise keep working only on it.";
                emit.accept(Events.of(runId, "checkpoint_progress_observed",
                        "Action changed state; planner must decide whether the active checkpoint is visibly satisfied",
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", checklist.activeText())
                                .build()));
            } else if (isNavigationAction(planned)) {
                lastObservation = lastObservation
                        + " [navigation] Navigation did not visibly change the screen; do not subdivide for this. "
                        + "Try another navigation direction/key, verify visible evidence, or continue from the current viewport.";
                emit.accept(Events.of(runId, "navigation_no_progress",
                        lastObservation,
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", checklist.activeText())
                                .build()));
            } else {
                int count = repair.recordNoProgress(plannedSignature, lastObservation);
                lastObservation = repair.repairPrompt(checklist.activeText(), lastObservation);
                if (count >= 1 && checklist.canSubdivide() && !repair.subdivisionRequested()) {
                    repair.markSubdivisionRequested();
                    lastObservation = lastObservation
                            + " [recursive todo] Emit subdivide_checkpoint with 2-3 smaller observable subtasks, or choose a different route before trying more actions.";
                }
                if (repair.shouldConsultExpert(count) && expertConsultant != null && expertConsultant.enabled()) {
                    emit.accept(Events.of(runId, "expert_consult_started",
                            "Text-only expert consult for active checkpoint",
                            Events.meta()
                                    .put("iteration", step)
                                    .put("checkpoint", checklist.activeText())
                                    .put("model", expertConsultant.model())
                                    .build()));
                    try {
                        ExpertConsultant.Advice advice = expertConsultant.ask(new ExpertConsultant.Question(
                                userTask,
                                checklist.activeText(),
                                planned.observation(),
                                planned.action(),
                                planned.elementDescription(),
                                planned.reason(),
                                planned.goalLink(),
                                planned.goalTrace(),
                                planned.assumption(),
                                planned.verifiedBy(),
                                lastObservation));
                        repair.markExpertConsulted();
                        if (advice.ok()) {
                            String text = truncate(advice.text(), 900);
                            lastObservation = lastObservation + " [expert advice] " + text;
                            emit.accept(Events.of(runId, "expert_consult_completed", text,
                                    Events.meta()
                                            .put("iteration", step)
                                            .put("checkpoint", checklist.activeText())
                                            .put("model", expertConsultant.model())
                                            .build()));
                        } else {
                            emit.accept(Events.of(runId, "expert_consult_failed",
                                    advice.text() == null ? "expert returned no advice" : advice.text(),
                                    Map.of("iteration", String.valueOf(step))));
                        }
                    } catch (RuntimeException ex) {
                        if (ex.getCause() instanceof InterruptedException) throw ex;
                        repair.markExpertConsulted();
                        emit.accept(Events.of(runId, "expert_consult_failed",
                                "Expert consult failed: " + ex.getMessage(),
                                Map.of("iteration", String.valueOf(step))));
                    }
                }
                emit.accept(Events.of(runId, count == 1 ? "calm_repair_started" : "calm_repair_continues",
                        lastObservation,
                        Events.meta()
                                .put("iteration", step)
                                .put("checkpoint", checklist.activeText())
                                .put("attempts", count)
                                .build()));
            }
            progressNote = updateProgress(progressNote, planned, resolved, anyOk, lastObservation);
        }

        emit.accept(Events.of(runId, "task_failed",
                "Step budget exhausted: " + stepBudget,
                Map.of("steps", String.valueOf(stepBudget))));
        return Result.failure("budget_exhausted", stepBudget);
    }

    /** Per-turn loop using a single Gemini Flash call (the {@link ProActor}). The actor sees
     *  the OmniParser-labeled screenshot + element list + progress note and emits the full
     *  next-action JSON in one shot — action verb plus the box id / coords / text / combo /
     *  path needed to dispatch directly to {@link Tools#invoke}. The XML &lt;tools&gt; format
     *  is not used at all.
     *
     *  <p>Pre-condition: {@link Tools} must be wired with an {@link OmniParserClient} so each
     *  screenshot is auto-labeled and {@link Tools#lastParse()} returns the element list. */
    private Result runProActorLoop(String runId, String userTask, Consumer<Events.Event> emit) {
        DoomGuard guard = new DoomGuard(Math.max(2, doomThreshold));
        String progressNote = "";
        String lastObservation = "";
        boolean producedVisualWork = false;

        for (int step = 1; step <= stepBudget; step++) {
            bailIfCancelled();
            emit.accept(Events.of(runId, "iteration_started", "Iteration " + step + "/" + stepBudget,
                    Map.of("iteration", String.valueOf(step), "flow", "pro-actor")));

            Tools.Outcome shotOut = tools.invoke("screenshot", MAPPER.createObjectNode());
            bailIfCancelled();
            byte[] labeledPng = shotOut.screenshotPng();
            OmniParserClient.ParseResult parse = tools.lastParse();
            List<OmniParserClient.Element> elements = parse == null ? List.of() : parse.elements();
            String screenshotKind = elements.isEmpty() ? "Screenshot" : "Labeled screenshot";
            emit.accept(Events.of(runId, "screenshot_taken",
                    screenshotKind + " · " + (labeledPng == null ? 0 : labeledPng.length) + " bytes · "
                            + elements.size() + " elements",
                    Events.meta()
                            .put("iteration", step)
                            .put("bytes", labeledPng == null ? 0 : labeledPng.length)
                            .put("elementCount", elements.size())
                            .put("labeled", !elements.isEmpty())
                            .build()));

            bailIfCancelled();
            ProActor.Step planned;
            try {
                planned = proActor.next(userTask, progressNote, lastObservation, labeledPng, elements);
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) throw ex;
                log.error("[agent.pa] pro-actor failed: {}", ex.toString());
                emit.accept(Events.of(runId, "model_failed", "Pro actor error: " + ex.getMessage(),
                        Map.of("model", proActor.model())));
                return Result.failure("pro_actor_failed: " + ex.getMessage(), step);
            }
            bailIfCancelled();

            if (planned.isParseError()) {
                String reason = "pro_actor_format_error: " + planned.parseError();
                emit.accept(Events.of(runId, "model_failed", reason,
                        Events.meta()
                                .put("iteration", step)
                                .put("model", proActor.model())
                                .put("error", planned.parseError())
                                .build()));
                emit.accept(Events.of(runId, "task_failed", reason,
                        Map.of("steps", String.valueOf(step))));
                return Result.failure(reason, step);
            }

            emit.accept(Events.of(runId, "step_planned",
                    planned.action() + (planned.box() != null ? " · box=" + planned.box() : "")
                            + (planned.target() == null ? "" : " · " + truncate(planned.target(), 80))
                            + (planned.reason() == null || planned.reason().isBlank() ? "" : " · because " + truncate(planned.reason(), 120))
                            + (planned.goalTrace() == null ? "" : " · " + truncate(planned.goalTrace(), 120))
                            + (planned.goalTrace() != null || planned.goalLink() == null ? "" : " · " + truncate(planned.goalLink(), 40)),
                    Events.meta()
                            .put("iteration", step)
                            .put("action", planned.action())
                            .put("box", planned.box() == null ? "" : planned.box().toString())
                            .put("x", planned.x() == null ? "" : planned.x().toString())
                            .put("y", planned.y() == null ? "" : planned.y().toString())
                            .put("text", planned.text() == null ? "" : planned.text())
                            .put("combo", planned.combo() == null ? "" : planned.combo())
                            .put("target", planned.target() == null ? "" : planned.target())
                            .put("reason", planned.reason() == null ? "" : planned.reason())
                            .put("goalLink", planned.goalLink() == null ? "" : planned.goalLink())
                            .put("goalTrace", planned.goalTrace() == null ? "" : planned.goalTrace())
                            .put("assumption", planned.assumption() == null ? "" : planned.assumption())
                            .put("verifiedBy", planned.verifiedBy() == null ? "" : planned.verifiedBy())
                            .build()));

            if (planned.isTerminal()) {
                if ("done".equals(planned.action())) {
                    if (!producedVisualWork) {
                        lastObservation = "[pro-actor] done rejected — no successful action ran yet";
                        emit.accept(Events.of(runId, "completion_rejected", lastObservation,
                                Map.of("iteration", String.valueOf(step))));
                        continue;
                    }
                    Verification verification = verifyVisualCompletion(runId, userTask, emit);
                    if (verification.isInconclusive()) {
                        // ProActor has no per-checkpoint checklist to fall back on; surface
                        // inconclusive (so the planner can react) rather than fabricate a
                        // REJECT from a parse failure.
                        emit.accept(Events.of(runId, "completion_inconclusive", verification.reason(),
                                Map.of("iteration", String.valueOf(step))));
                        lastObservation = "[verifier-inconclusive] " + verification.reason();
                        continue;
                    }
                    if (!verification.ok()) {
                        emit.accept(Events.of(runId, "completion_rejected", verification.reason(),
                                Map.of("iteration", String.valueOf(step))));
                        lastObservation = "[verifier] " + verification.reason();
                        continue;
                    }
                    emit.accept(Events.of(runId, "completion_verified", verification.reason(),
                            Map.of("iteration", String.valueOf(step))));
                    String summary = planned.summary() == null ? "task completed" : planned.summary();
                    emit.accept(Events.of(runId, "task_completed", summary,
                            Map.of("steps", String.valueOf(step))));
                    return Result.success(summary, step);
                }
                String reason = planned.summary() == null ? "fail without reason" : planned.summary();
                emit.accept(Events.of(runId, "task_failed", reason,
                        Map.of("steps", String.valueOf(step))));
                return Result.failure(reason, step);
            }

            ResolvedCall resolved = resolveProStep(planned, elements, runId, step, emit);
            if (resolved == null) {
                lastObservation = "[pro-actor] step skipped: no actionable tool resolved";
                continue;
            }

            String blocked = guard.check(resolved.toolName(), resolved.args().toString());
            if (blocked != null) {
                emit.accept(Events.of(runId, "tool_blocked", "doom_loop",
                        Map.of("tool", resolved.toolName(), "iteration", String.valueOf(step))));
                emit.accept(Events.of(runId, "tool_failed", blocked,
                        Map.of("tool", resolved.toolName())));
                lastObservation = "[doom_guard] " + blocked;
                continue;
            }

            emit.accept(Events.of(runId, "tool_called", resolved.toolName(),
                    Events.meta()
                            .put("name", resolved.toolName())
                            .put("arguments", resolved.args().toString())
                            .put("reason", planned.reason() == null ? "" : planned.reason())
                            .put("iteration", step)
                            .build()));
            bailIfCancelled();
            Tools.Outcome out;
            try {
                out = tools.invoke(resolved.toolName(), resolved.args());
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof InterruptedException) throw ex;
                out = Tools.Outcome.fail("tool threw: " + ex.getMessage());
            }
            bailIfCancelled();
            emit.accept(Events.of(runId, out.ok() ? "tool_completed" : "tool_failed", out.summary(),
                    Map.of("name", resolved.toolName(), "iteration", String.valueOf(step))));
            if (out.ok() && countsAsVisualWork(resolved.toolName(), out)) producedVisualWork = true;

            lastObservation = "[" + resolved.toolName() + "] ok=" + out.ok() + " · " + truncate(out.summary(), 240);
            progressNote = updateProgress(progressNote, planned, resolved, out);
        }

        emit.accept(Events.of(runId, "task_failed",
                "Step budget exhausted: " + stepBudget,
                Map.of("steps", String.valueOf(stepBudget))));
        return Result.failure("budget_exhausted", stepBudget);
    }

    /** Translate the {@link ProActor.Step} into a concrete {@link Tools#invoke} call.
     *  The actor already chose the box id / coords inline, so this is just a thin field
     *  router — no second LLM call. Returns {@code null} when the actor emitted an
     *  unactionable step (click without box or coords, hotkey without combo, ...). */
    private List<ResolvedCall> resolveSeeActStep(SeeActPlanner.Step planned, byte[] rawPng,
            GroundingState groundingState, String runId, int step, Consumer<Events.Event> emit,
            RecursiveTodoState checklist) {
        List<ResolvedCall> calls = new ArrayList<>();
        // Drag with two anchors. Three input shapes accepted:
        //   1) raw pixels: from_x/from_y + to_x/to_y → forward as-is.
        //   2) descriptions: from_description + to_description → ground both
        //      via UGround in parallel, then build drag args.
        //   3) box ids: box + to_box → forward as-is (sandbox resolves boxes
        //      to centroids server-side).
        if ("drag".equals(planned.action()) || "drag_to".equals(planned.action())
                || "drag_between".equals(planned.action())) {
            ObjectNode args = MAPPER.createObjectNode();
            // Shape 1: raw pixels
            if (planned.fromX() != null && planned.fromY() != null
                    && planned.toX() != null && planned.toY() != null) {
                args.put("from_x", planned.fromX()); args.put("from_y", planned.fromY());
                args.put("to_x",   planned.toX());   args.put("to_y",   planned.toY());
            } else if (planned.fromDescription() != null && !planned.fromDescription().isBlank()
                    && planned.toDescription() != null && !planned.toDescription().isBlank()) {
                // Shape 2: ground both descriptions in parallel
                if (uGround == null || !uGround.enabled()) {
                    emit.accept(Events.of(runId, "step_skipped",
                            "drag with descriptions requires UGround; it is disabled. Pick raw from_x/from_y/to_x/to_y or drag_box.",
                            Map.of("iteration", String.valueOf(step))));
                    return List.of();
                }
                String fromDesc = planned.fromDescription();
                String toDesc = planned.toDescription();
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newFixedThreadPool(2);
                java.util.concurrent.Future<UGroundClient.GroundedPoint> fa =
                        pool.submit(() -> uGround.locate(rawPng, fromDesc));
                java.util.concurrent.Future<UGroundClient.GroundedPoint> fb =
                        pool.submit(() -> uGround.locate(rawPng, toDesc));
                pool.shutdown();
                UGroundClient.GroundedPoint a, b;
                try {
                    a = fa.get();
                    b = fb.get();
                } catch (Exception ex) {
                    emit.accept(Events.of(runId, "step_skipped",
                            "drag grounding failed: " + truncate(ex.toString(), 200),
                            Map.of("iteration", String.valueOf(step))));
                    return List.of();
                }
                args.put("from_x", a.x()); args.put("from_y", a.y());
                args.put("to_x",   b.x()); args.put("to_y",   b.y());
                emit.accept(Events.of(runId, "drag_grounded",
                        "from='" + truncate(fromDesc, 40) + "' (" + a.x() + "," + a.y()
                                + ") → to='" + truncate(toDesc, 40) + "' (" + b.x() + "," + b.y() + ")",
                        Events.meta()
                                .put("iteration", step)
                                .put("fromDescription", fromDesc)
                                .put("toDescription", toDesc)
                                .put("fromX", String.valueOf(a.x()))
                                .put("fromY", String.valueOf(a.y()))
                                .put("toX",   String.valueOf(b.x()))
                                .put("toY",   String.valueOf(b.y()))
                                .build()));
            } else {
                emit.accept(Events.of(runId, "step_skipped",
                        "drag needs either (from_x,from_y,to_x,to_y) or (from_description,to_description) or (box,to_box).",
                        Map.of("iteration", String.valueOf(step))));
                return List.of();
            }
            if (planned.durationSeconds() != null) {
                args.put("duration", planned.durationSeconds());
            }
            calls.add(new ResolvedCall("drag", args));
            return calls;
        }
        if ("drag_box".equals(planned.action()) || "left_click_drag".equals(planned.action())) {
            if (planned.fromBox() == null || planned.toBox() == null) {
                emit.accept(Events.of(runId, "step_skipped",
                        "drag_box needs box (from id) and to_box (target id) — read both ids from the labeled screenshot.",
                        Map.of("iteration", String.valueOf(step))));
                return List.of();
            }
            ObjectNode args = MAPPER.createObjectNode();
            args.put("box", planned.fromBox());
            args.put("to_box", planned.toBox());
            calls.add(new ResolvedCall("drag_box", args));
            return calls;
        }
        if (planned.isClickOnly()) {
            UGroundClient.GroundedPoint point = resolvePoint(planned, rawPng, groundingState, runId, step, emit, checklist);
            if (point == null) return List.of();
            ObjectNode args = MAPPER.createObjectNode();
            args.put("x", point.x());
            args.put("y", point.y());
            calls.add(new ResolvedCall(planned.action(), args));
            return calls;
        }
        if (planned.isType()) {
            Integer fieldX = null;
            Integer fieldY = null;
            boolean replaceMode = planned.mode() != null
                    && "replace".equalsIgnoreCase(planned.mode().trim());
            if (planned.textToType() == null) {
                emit.accept(Events.of(runId, "step_skipped",
                        "type_text without text_to_type",
                        Map.of("iteration", String.valueOf(step))));
                return List.of();
            }
            if (replaceMode && planned.hasCoords()) {
                fieldX = planned.x();
                fieldY = planned.y();
                evidenceStore.putActionPoint(taskSeqOf(checklist), fieldX, fieldY, planned.action());
            }
            ObjectNode typeArgs = MAPPER.createObjectNode();
            typeArgs.put("text", planned.textToType());
            if (planned.mode() != null && !planned.mode().isBlank()) {
                typeArgs.put("mode", planned.mode());
            }
            // type_text is a focused-keyboard action, not a grounding action.
            // The planner must click first when focus is wrong. Replace mode
            // may receive explicit x/y to focus before clearing; an
            // element_description is only descriptive and must not force
            // UGround, because the field can already be focused.
            if (fieldX != null && fieldY != null) {
                typeArgs.put("x", fieldX);
                typeArgs.put("y", fieldY);
            }
            calls.add(new ResolvedCall("type_text", typeArgs));
            return calls;
        }

        ObjectNode args = MAPPER.createObjectNode();
        switch (planned.action()) {
            case "hotkey":
            case "key":
            case "press_key": {
                if (planned.combo() == null || planned.combo().isBlank()) return List.of();
                args.put("combo", planned.combo());
                return List.of(new ResolvedCall("hotkey", args));
            }
            case "scroll": {
                args.put("dx", planned.dx() == null ? 0 : planned.dx());
                args.put("dy", planned.dy() == null ? 0 : planned.dy());
                return List.of(new ResolvedCall("scroll", args));
            }
            case "wait": {
                args.put("ms", planned.ms() == null ? 500 : planned.ms());
                return List.of(new ResolvedCall("wait", args));
            }
            case "list_apps":
            case "apps_catalog": {
                args.put("limit", 20);
                if (planned.target() != null && !planned.target().isBlank()) {
                    args.put("query", planned.target());
                }
                return List.of(new ResolvedCall("list_apps", args));
            }
            case "write_file": {
                if (planned.path() == null || planned.path().isBlank()) return List.of();
                args.put("path", planned.path());
                args.put("content", planned.content() == null ? "" : planned.content());
                return List.of(new ResolvedCall("write_file", args));
            }
            case "read_file": {
                if (planned.path() == null || planned.path().isBlank()) return List.of();
                args.put("path", planned.path());
                return List.of(new ResolvedCall("read_file", args));
            }
            case "launch_app": {
                if (!putLaunchArgs(args, planned.target(), planned.desktopFile(), planned.exec())) {
                    return List.of();
                }
                return List.of(new ResolvedCall("launch_app", args));
            }
            case "activate_window", "window_activate", "activate_app", "focus_window", "focus_app": {
                String target = planned.target();
                if (target == null || target.isBlank()) return List.of();
                args.put("name", target);
                return List.of(new ResolvedCall("activate_window", args));
            }
            case "close_window", "window_close", "close_app", "quit_app": {
                String target = planned.target();
                if (target == null || target.isBlank()) return List.of();
                args.put("name", target);
                return List.of(new ResolvedCall("close_window", args));
            }
            default:
                emit.accept(Events.of(runId, "step_skipped",
                        "unknown action: " + planned.action(),
                        Map.of("iteration", String.valueOf(step))));
                return List.of();
        }
    }

    private UGroundClient.GroundedPoint resolvePoint(SeeActPlanner.Step planned, byte[] rawPng,
            GroundingState groundingState, String runId, int step, Consumer<Events.Event> emit,
            RecursiveTodoState checklist) {
        if (planned.hasCoords()) {
            // Record planner-supplied coordinates as an action point
            evidenceStore.putActionPoint(taskSeqOf(checklist), planned.x(), planned.y(), planned.action());
            return new UGroundClient.GroundedPoint(planned.x(), planned.y(), "planner");
        }
        if (!groundingState.available) {
            groundingState.note = "[grounding] UGround unavailable; planner must emit x/y coordinates for pointer actions";
            emit.accept(Events.of(runId, "step_skipped",
                    planned.action() + " needs x/y but UGround is disabled",
                    Map.of("iteration", String.valueOf(step))));
            return null;
        }
        if (planned.elementDescription() == null || planned.elementDescription().isBlank()) {
            groundingState.note = "[seeact] pointer action had no element_description or x/y";
            emit.accept(Events.of(runId, "step_skipped",
                    planned.action() + " without element_description",
                    Map.of("iteration", String.valueOf(step))));
            return null;
        }
        bailIfCancelled();
        try {
            String desc = planned.elementDescription();
            String region = planned.screenRegion();
            String query = (region == null || region.isBlank())
                    ? desc
                    : desc + " (located in the " + region.trim() + " of the screen)";
            UGroundClient.GroundedPoint point = uGround.locate(rawPng, query);
            groundingState.consecutiveFailures = 0;
            groundingState.note = "";
            emit.accept(Events.of(runId, "target_grounded",
                    planned.elementDescription() + " -> (" + point.x() + "," + point.y() + ")",
                    Events.meta()
                            .put("iteration", step)
                            .put("model", uGround.model())
                            .put("elementDescription", planned.elementDescription())
                            .put("x", point.x())
                            .put("y", point.y())
                            .build()));
            // Record grounded action point into evidence store
            evidenceStore.putActionPoint(taskSeqOf(checklist), point.x(), point.y(), planned.action());
            return point;
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            groundingState.consecutiveFailures++;
            groundingState.note = "[grounding] UGround failed " + groundingState.consecutiveFailures
                    + "/" + GroundingState.FAILURE_LIMIT + ": " + truncate(ex.getMessage(), 180);
            emit.accept(Events.of(runId, "grounding_failed",
                    "UGround error: " + ex.getMessage(),
                    Events.meta()
                            .put("iteration", step)
                            .put("consecutiveFailures", groundingState.consecutiveFailures)
                            .build()));
            if (groundingState.consecutiveFailures >= GroundingState.FAILURE_LIMIT) {
                groundingState.available = false;
                groundingState.note = groundingState.note
                        + " UGround disabled for this run; next pointer action must include x/y.";
                emit.accept(Events.of(runId, "grounding_disabled",
                        "UGround disabled for this run after "
                                + groundingState.consecutiveFailures + " consecutive failures",
                        Events.meta()
                                .put("iteration", step)
                                .put("fallback", "planner_xy")
                                .build()));
            }
            return null;
        }
    }

    private ResolvedCall resolveProStep(ProActor.Step planned, List<OmniParserClient.Element> elements,
            String runId, int step, Consumer<Events.Event> emit) {
        ObjectNode args = MAPPER.createObjectNode();
        if (planned.isClick()) {
            if (planned.hasBox()) {
                int id = planned.box();
                if (id < 0 || id >= elements.size()) {
                    emit.accept(Events.of(runId, "step_skipped",
                            "out-of-range box id " + id + " (have " + elements.size() + " elements)",
                            Map.of("iteration", String.valueOf(step))));
                    return null;
                }
                if ("click".equals(planned.action())) {
                    args.put("id", id);
                    return new ResolvedCall("click_box", args);
                }
                OmniParserClient.Element e = elements.get(id);
                args.put("x", e.centerX());
                args.put("y", e.centerY());
                return new ResolvedCall(planned.action(), args);
            }
            if (planned.hasCoords()) {
                args.put("x", planned.x());
                args.put("y", planned.y());
                return new ResolvedCall(planned.action(), args);
            }
            emit.accept(Events.of(runId, "step_skipped",
                    planned.action() + " without box or coords",
                    Map.of("iteration", String.valueOf(step))));
            return null;
        }
        switch (planned.action()) {
            case "type":
            case "type_text": {
                args.put("text", planned.text() == null ? "" : planned.text());
                if (planned.mode() != null) args.put("mode", planned.mode());
                return new ResolvedCall("type_text", args);
            }
            case "hotkey":
            case "key":
            case "press_key": {
                if (planned.combo() == null || planned.combo().isBlank()) return null;
                args.put("combo", planned.combo());
                return new ResolvedCall("hotkey", args);
            }
            case "scroll": {
                args.put("dx", planned.dx() == null ? 0 : planned.dx());
                args.put("dy", planned.dy() == null ? 0 : planned.dy());
                return new ResolvedCall("scroll", args);
            }
            case "wait": {
                args.put("ms", planned.ms() == null ? 500 : planned.ms());
                return new ResolvedCall("wait", args);
            }
            case "list_apps":
            case "apps_catalog": {
                args.put("limit", 20);
                if (planned.target() != null && !planned.target().isBlank()) {
                    args.put("query", planned.target());
                }
                return new ResolvedCall("list_apps", args);
            }
            case "write_file": {
                if (planned.path() == null || planned.path().isBlank()) return null;
                args.put("path", planned.path());
                args.put("content", planned.content() == null ? "" : planned.content());
                return new ResolvedCall("write_file", args);
            }
            case "read_file": {
                if (planned.path() == null || planned.path().isBlank()) return null;
                args.put("path", planned.path());
                return new ResolvedCall("read_file", args);
            }
            case "launch_app": {
                if (!putLaunchArgs(args, planned.target(), planned.desktopFile(), planned.exec())) {
                    return null;
                }
                return new ResolvedCall("launch_app", args);
            }
            case "activate_window", "window_activate", "activate_app", "focus_window", "focus_app": {
                String target = planned.target();
                if (target == null || target.isBlank()) return null;
                args.put("name", target);
                return new ResolvedCall("activate_window", args);
            }
            default:
                emit.accept(Events.of(runId, "step_skipped",
                        "unknown action: " + planned.action(),
                        Map.of("iteration", String.valueOf(step))));
                return null;
        }
    }

    static boolean putLaunchArgs(ObjectNode args, String name, String desktopFile, String exec) {
        boolean any = false;
        if (name != null && !name.isBlank()) {
            args.put("name", name);
            any = true;
        }
        if (desktopFile != null && !desktopFile.isBlank()) {
            args.put("desktop_file", desktopFile);
            any = true;
        }
        if (exec != null && !exec.isBlank()) {
            args.put("exec", exec);
            any = true;
        }
        return any;
    }

    /** Tiny fallback "progress note" updater. Not an LLM call — just keeps a 600-char rolling
     *  text so the next pro-actor turn has a brief recap to read. */
    private static String updateProgress(String prior, ProActor.Step planned,
            ResolvedCall resolved, Tools.Outcome out) {
        StringBuilder sb = new StringBuilder();
        if (prior != null && !prior.isBlank()) {
            sb.append(prior.trim()).append(' ');
        }
        sb.append("[").append(resolved.toolName()).append(" ")
                .append(out.ok() ? "ok" : "fail").append("]");
        if (planned.target() != null) {
            sb.append(" target='").append(truncate(planned.target(), 60)).append("'");
        }
        if (planned.text() != null) {
            sb.append(" text='").append(truncate(planned.text(), 60)).append("'");
        }
        if (out.summary() != null) {
            sb.append(" -> ").append(truncate(out.summary(), 100));
        }
        sb.append('.');
        String s = sb.toString();
        if (s.length() > 600) s = s.substring(s.length() - 600);
        return s;
    }

    private static String updateProgress(String prior, SeeActPlanner.Step planned,
            List<ResolvedCall> resolved, boolean anyOk, String observation) {
        StringBuilder sb = new StringBuilder();
        if (prior != null && !prior.isBlank()) {
            sb.append(prior.trim()).append(' ');
        }
        sb.append("[").append(planned.action()).append(anyOk ? " ok" : " fail").append("]");
        if (planned.elementDescription() != null) {
            sb.append(" target='").append(truncate(planned.elementDescription(), 60)).append("'");
        }
        if (planned.textToType() != null) {
            sb.append(" text='").append(truncate(planned.textToType(), 60)).append("'");
        }
        if (resolved != null && !resolved.isEmpty()) {
            sb.append(" tools=");
            for (int i = 0; i < resolved.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(resolved.get(i).toolName());
            }
        }
        if (observation != null && !observation.isBlank()) {
            sb.append(" -> ").append(truncate(observation, 120));
        }
        sb.append('.');
        String s = sb.toString();
        if (s.length() > 600) s = s.substring(s.length() - 600);
        return s;
    }

    private static String joinSummaries(List<String> summaries) {
        if (summaries == null || summaries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String summary : summaries) {
            if (summary == null || summary.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(summary);
        }
        return truncate(sb.toString(), 1800);
    }

    private static void applyChecklist(String runId, int step, RecursiveTodoState state,
            SeeActPlanner.Step planned, Consumer<Events.Event> emit) {
        if (!state.initialized() && !planned.checklist().isEmpty()) {
            state.initialize(planned.checklist());
            emit.accept(Events.of(runId, "task_checklist_created",
                    state.summary(),
                    Events.meta()
                            .put("iteration", step)
                            .put("items", state.size())
                            .build()));
        }
    }

    private String pushAutoSubtasks(String runId, int step, RecursiveTodoState checklist,
            CalmRepairState repair, String reason, String context, boolean preserveFailedTarget,
            Consumer<Events.Event> emit) {
        return pushAutoSubtasks(runId, step, checklist, repair, reason, context, preserveFailedTarget, null, emit);
    }

    private String pushAutoSubtasks(String runId, int step, RecursiveTodoState checklist,
            CalmRepairState repair, String reason, String context, boolean preserveFailedTarget,
            String userTask, Consumer<Events.Event> emit) {
        RecursiveTodoState.Splitter splitter = checkpointSplitter == null ? null
                : (parentText, splitReason, splitContext) -> checkpointSplitter.split(
                        userTask, parentText, splitReason, splitContext);
        // Capture the parent id BEFORE subdivision so we can drop its stale
        // blame rows once it's been replaced by children. Without this, every
        // child inherits the parent's old "tried X, didn't work" entries via
        // BlameLedger.ancestorRows(), and the planner sees ghost invalidations
        // (e.g. a LinkedIn-field type_text shown under unrelated EEO subtasks)
        // on every turn forever.
        String preSubdivideId = activeCheckpointId(checklist);
        if (!checklist.pushAutoSubtasks(reason, context, splitter)) return null;
        if (preSubdivideId != null && !preSubdivideId.isBlank()) {
            blameLedger.clearInvalidations(preSubdivideId);
        }
        if (preserveFailedTarget) {
            repair.afterSubdivision();
        } else {
            repair.clear();
        }
        String observation = "[todo] active checkpoint auto-subdivided; next action must serve "
                + checklist.activeText();
        emit.accept(Events.of(runId, "task_checklist_updated",
                checklist.summary(),
                Events.meta()
                        .put("iteration", step)
                        .put("checkpoint", checklist.activeText())
                        .put("depth", checklist.depth())
                        .put("source", "recursive_todo_auto")
                        .put("reason", reason == null ? "" : truncate(reason, 160))
                        .build()));
        return observation;
    }

    private static String activeCheckpointId(RecursiveTodoState checklist) {
        if (checklist == null || !checklist.initialized()) return "";
        String text = checklist.activeText();
        int colon = text == null ? -1 : text.indexOf(':');
        return colon > 0 ? text.substring(0, colon).trim() : "";
    }

    /** Return (or assign) the task sequence number for the active checkpoint.
     *  New checkpoints receive the next integer and their start timestamp is recorded. */
    private int taskSeqOf(RecursiveTodoState checklist) {
        String id = activeCheckpointId(checklist);
        if (id.isBlank()) id = "__root__";
        return taskSeqByCheckpointId.computeIfAbsent(id, k -> {
            int seq = ++taskSeqCounter;
            taskStartedTsBySeq.put(seq, System.currentTimeMillis());
            return seq;
        });
    }

    /** Record non-pointer action (hotkey / type_text) into the per-task action timeline.
     *  Pointer actions are already captured at grounding time; this fills the gap so the
     *  verifier sees the full sequence (clicks + hotkeys + typed text) per task. */
    private void recordNonPointerAction(RecursiveTodoState checklist, ResolvedCall call) {
        if (call == null) return;
        String tool = call.toolName();
        if (tool == null) return;
        com.fasterxml.jackson.databind.JsonNode args = call.args();
        switch (tool) {
            case "hotkey", "key", "press_key" -> {
                String combo = args == null ? "" : args.path("combo").asText("");
                if ((combo == null || combo.isBlank()) && args != null && args.path("keys").isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (com.fasterxml.jackson.databind.JsonNode k : args.path("keys")) {
                        if (sb.length() > 0) sb.append('+');
                        sb.append(k.asText(""));
                    }
                    combo = sb.toString();
                }
                if (combo != null && !combo.isBlank()) {
                    evidenceStore.putHotkey(taskSeqOf(checklist), combo);
                }
            }
            case "type_text", "type" -> {
                String text = args == null ? "" : args.path("text").asText("");
                if (text != null && !text.isEmpty()) {
                    evidenceStore.putTypeText(taskSeqOf(checklist), text);
                }
            }
            default -> { /* pointer actions handled at grounding; others ignored */ }
        }
    }

    /** Decide whether a planner action goes on the blame ledger. Meta and
     *  no-op actions (verify/subdivide/done/fail/wait) are not tracked because
     *  they do not represent a route to be invalidated. */
    private static boolean ledgerTracked(String action) {
        if (action == null) return false;
        return switch (action) {
            case "click", "double_click", "right_click",
                    "type_text", "type",
                    "hotkey",
                    "launch_app", "activate_window", "window_activate", "activate_app", "focus_window", "focus_app", "list_apps",
                    "close_window", "window_close", "close_app", "quit_app",
                    "write_file", "read_file" -> true;
            default -> false;
        };
    }

    private static String describePlanned(SeeActPlanner.Step planned) {
        String[] candidates = {
                planned.elementDescription(), planned.target(),
                planned.desktopFile(), planned.exec(),
                planned.path(), planned.combo()
        };
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return planned.action() == null ? "" : planned.action();
    }

    /** Map a list of {@link Tools.Outcome} to a single ledger status:
     *  TOOL_FAILED if any call failed; NO_CHANGE if all calls succeeded but no
     *  visible change was produced; PENDING otherwise (the verifier later
     *  resolves it with VERIFIER_ACCEPT or VERIFIER_REJECT). */
    private static BlameLedger.Status classifyOutcomeStatus(List<Tools.Outcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return BlameLedger.Status.PENDING;
        boolean allOk = true;
        boolean anyVisibleChange = false;
        for (Tools.Outcome out : outcomes) {
            if (out == null || !out.ok()) { allOk = false; break; }
            if (!hasNoVisibleChange(out)) anyVisibleChange = true;
        }
        if (!allOk) return BlameLedger.Status.TOOL_FAILED;
        if (!anyVisibleChange) return BlameLedger.Status.NO_CHANGE;
        return BlameLedger.Status.PENDING;
    }

    private static int countMatches(java.util.Collection<String> haystack, String needle) {
        if (haystack == null || needle == null) return 0;
        int n = 0;
        for (String s : haystack) if (needle.equals(s)) n++;
        return n;
    }

    private static String routeHashFor(SeeActPlanner.Step planned) {
        return BlameLedger.routeHash(
                planned.action(),
                describePlanned(planned),
                planned.x(), planned.y(),
                planned.combo(), planned.textToType());
    }

    /** Canonical post-grounding route hash. Built from the first resolved call's
     *  args so that the pixel bucket beats turn-to-turn description churn. The
     *  planner's narration of the same UI element drifts ("Save as... Ctrl+S"
     *  vs "Save as... menu item"); the grounded xy does not. */
    private static String canonicalRouteHash(SeeActPlanner.Step planned,
            List<ResolvedCall> resolved) {
        if (resolved == null || resolved.isEmpty()) return null;
        ResolvedCall first = resolved.get(0);
        String tool = first.toolName();
        if (tool == null || tool.isBlank()) return null;
        com.fasterxml.jackson.databind.JsonNode args = first.args();
        Integer x = args != null && args.has("x") && args.get("x").canConvertToInt()
                ? args.get("x").asInt() : null;
        Integer y = args != null && args.has("y") && args.get("y").canConvertToInt()
                ? args.get("y").asInt() : null;
        String combo = args != null && args.has("combo") ? args.get("combo").asText(null) : null;
        String text = args != null && args.has("text") ? args.get("text").asText(null) : null;
        String desc = planned == null ? null : describePlanned(planned);
        return BlameLedger.routeHash(tool, desc, x, y, combo, text);
    }

    private static Map<String, String> toolMetadata(String toolName, int step, Tools.Outcome out) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("name", toolName);
        meta.put("iteration", String.valueOf(step));
        if (out != null && out.metadata() != null) {
            meta.putAll(out.metadata());
        }
        return meta;
    }

    private static String observationSummary(String toolName, Tools.Outcome out) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(toolName).append("] ok=").append(out.ok());
        if (out.metadata() != null && !out.metadata().isEmpty()) {
            String visible = out.metadata().get("visible_change");
            String delta = out.metadata().get("screen_delta_pct");
            if (visible != null || delta != null) {
                sb.append(" visible_change=").append(visible == null ? "unknown" : visible);
                if (delta != null) sb.append(" screen_delta_pct=").append(delta);
            }
        }
        int maxSummary = "list_apps".equals(toolName) || "apps_catalog".equals(toolName) ? 1600 : 200;
        sb.append(" · ").append(truncate(out.summary(), maxSummary));
        return sb.toString();
    }

    private static boolean checkpointProgressed(List<ResolvedCall> calls, List<Tools.Outcome> outcomes) {
        if (calls == null || outcomes == null) return false;
        for (int i = 0; i < calls.size() && i < outcomes.size(); i++) {
            Tools.Outcome outcome = outcomes.get(i);
            if (outcome == null || !outcome.ok() || outcome.done()) continue;
            String toolName = calls.get(i).toolName();
            if (countsAsVisualWork(toolName, outcome)) return true;
            if (countsAsBackendProgress(toolName)) return true;
        }
        return false;
    }

    private static boolean countsAsBackendProgress(String toolName) {
        return List.of("write_file", "launch_app", "activate_window", "close_window").contains(toolName);
    }

    static final class CalmRepairState {
        private static final int MAX_BLOCKS = 3;

        private CallSignature lastNoProgressCall;
        private String lastNoProgressObservation = "";
        private int noProgressCount;
        private int blockedCount;
        private boolean expertConsulted;
        private boolean subdivisionRequested;

        boolean shouldBlock(CallSignature current) {
            return current != null && lastNoProgressCall != null
                    && lastNoProgressCall.sameTarget(current);
        }

        int recordNoProgress(CallSignature signature, String observation) {
            if (signature != null && lastNoProgressCall != null
                    && lastNoProgressCall.sameTarget(signature)) {
                noProgressCount++;
            } else {
                noProgressCount = 1;
            }
            lastNoProgressCall = signature;
            lastNoProgressObservation = observation == null ? "" : truncate(observation.trim(), 180);
            blockedCount = 0;
            return noProgressCount;
        }

        boolean shouldConsultExpert(int attempts) {
            return attempts >= 2 && !expertConsulted;
        }

        void markExpertConsulted() {
            expertConsulted = true;
        }

        boolean subdivisionRequested() {
            return subdivisionRequested;
        }

        void markSubdivisionRequested() {
            subdivisionRequested = true;
            blockedCount = 0;
        }

        int recordBlocked() {
            blockedCount++;
            return blockedCount;
        }

        boolean exhausted() {
            return blockedCount >= MAX_BLOCKS;
        }

        void clear() {
            lastNoProgressCall = null;
            lastNoProgressObservation = "";
            noProgressCount = 0;
            blockedCount = 0;
            expertConsulted = false;
            subdivisionRequested = false;
        }

        void afterSubdivision() {
            noProgressCount = 0;
            blockedCount = 0;
            expertConsulted = false;
            subdivisionRequested = false;
        }

        String blockMessage(String checkpoint, CallSignature repeated) {
            StringBuilder sb = new StringBuilder();
            sb.append("[repair] blocked repeated action");
            String what = repeated == null ? "" : repeated.describe();
            if (!what.isBlank()) {
                sb.append(" (").append(what).append(")");
            }
            sb.append(" for pending checkpoint");
            if (checkpoint != null && !checkpoint.isBlank()) {
                sb.append(" ").append(checkpoint);
            }
            sb.append(". The previous try made no progress");
            if (!lastNoProgressObservation.isBlank()) {
                sb.append(": ").append(lastNoProgressObservation);
            }
            sb.append(". Choose a different route. If a dialog ignored a click, use Escape, focus its title bar, wait, or use a keyboard shortcut.");
            if (blockedCount >= 1) {
                sb.append(" This route has failed once. Adapt with a workaround, keyboard path, navigation recovery, alternate source, or subdivide_checkpoint into smaller observable subtasks.");
            }
            return sb.toString();
        }

        String repairPrompt(String checkpoint, String observation) {
            StringBuilder sb = new StringBuilder();
            sb.append("[repair] current checkpoint still pending");
            if (checkpoint != null && !checkpoint.isBlank()) {
                sb.append(": ").append(checkpoint);
            }
            sb.append(". Last action did not create backend-observed progress. ");
            sb.append("Do not repeat the same target; recover the UI state or choose another route.");
            if (noProgressCount >= 1) {
                sb.append(" This route has failed once. Adapt with a workaround, keyboard path, navigation recovery, alternate source, or subdivide_checkpoint into smaller observable subtasks.");
            }
            if (observation != null && !observation.isBlank()) {
                sb.append(" Last result: ").append(truncate(observation, 240));
            }
            return sb.toString();
        }
    }

    static final class CallSignature {
        private static final int POINTER_TOLERANCE_PX = 4;

        private final String toolName;
        private final Integer x;
        private final Integer y;
        private final String value;

        private CallSignature(String toolName, Integer x, Integer y, String value) {
            this.toolName = norm(toolName);
            this.x = x;
            this.y = y;
            this.value = norm(value);
        }

        static CallSignature from(ResolvedCall call) {
            if (call == null) return null;
            ObjectNode args = call.args();
            Integer x = args != null && args.has("x") ? args.get("x").asInt() : null;
            Integer y = args != null && args.has("y") ? args.get("y").asInt() : null;
            String value = "";
            if (args != null) {
                if (args.has("combo")) value = args.get("combo").asText("");
                else if (args.has("text")) value = args.get("text").asText("");
                else if (args.has("name")) value = args.get("name").asText("");
                else if (args.has("path")) value = args.get("path").asText("");
            }
            return new CallSignature(call.toolName(), x, y, value);
        }

        static CallSignature of(String toolName, Integer x, Integer y, String value) {
            return new CallSignature(toolName, x, y, value);
        }

        boolean sameTarget(CallSignature other) {
            if (other == null || !toolName.equals(other.toolName)) return false;
            if (x != null && y != null && other.x != null && other.y != null) {
                return Math.abs(x - other.x) <= POINTER_TOLERANCE_PX
                        && Math.abs(y - other.y) <= POINTER_TOLERANCE_PX;
            }
            return !value.isBlank() && value.equals(other.value);
        }

        String describe() {
            if (x != null && y != null) {
                return toolName + " at " + x + "," + y;
            }
            if (!value.isBlank()) {
                return toolName + " " + value;
            }
            return toolName;
        }

        private static String norm(String s) {
            return s == null ? "" : s.trim().toLowerCase();
        }
    }

    private static final class GroundingState {
        private static final int FAILURE_LIMIT = 2;

        private boolean available;
        private int consecutiveFailures;
        private String note = "";

        private GroundingState(boolean available) {
            this.available = available;
        }

        private boolean coordinateFallbackAllowed() {
            return !available;
        }

        private String consumeNote() {
            String out = note == null ? "" : note;
            note = "";
            return out;
        }
    }

    private record ResolvedCall(String toolName, ObjectNode args) {}
    private record EvidenceNavResult(boolean dispatched, String observation) {}

    private EvidenceNavResult performEvidenceNavigation(String runId, int step,
            String checkpointId, Verification verification, Map<String, Integer> movesByCheckpoint,
            Consumer<Events.Event> emit) {
        String id = checkpointId == null ? "" : checkpointId;
        int used = movesByCheckpoint.getOrDefault(id, 0);
        if (used >= MAX_EVIDENCE_NAV_MOVES) {
            String observation = "[checkpoint needs evidence] navigation budget exhausted for "
                    + id + ": " + verification.reason();
            emit.accept(Events.of(runId, "checkpoint_needs_evidence_budget_exhausted",
                    observation,
                    Events.meta()
                            .put("iteration", step)
                            .put("checkpointId", id)
                            .put("moves", used)
                            .put("reason", verification.reason())
                            .build()));
            return new EvidenceNavResult(false, observation);
        }

        ResolvedCall nav = evidenceNavigationCall(verification.navigation(), used);
        int move = used + 1;
        movesByCheckpoint.put(id, move);
        emit.accept(Events.of(runId, "checkpoint_needs_evidence",
                verification.reason(),
                Events.meta()
                        .put("iteration", step)
                        .put("checkpointId", id)
                        .put("navigation", verification.navigation() == null ? "" : verification.navigation())
                        .put("move", move)
                        .put("maxMoves", MAX_EVIDENCE_NAV_MOVES)
                        .build()));
        emit.accept(Events.of(runId, "tool_called", nav.toolName(),
                Events.meta()
                        .put("name", nav.toolName())
                        .put("arguments", nav.args().toString())
                        .put("reason", "collect evidence for active checkpoint")
                        .put("iteration", step)
                        .build()));
        bailIfCancelled();
        Tools.Outcome out;
        try {
            out = tools.invoke(nav.toolName(), nav.args());
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            out = Tools.Outcome.fail("evidence navigation threw: " + ex.getMessage());
        }
        bailIfCancelled();
        emit.accept(Events.of(runId, out.ok() ? "tool_completed" : "tool_failed",
                out.summary(), toolMetadata(nav.toolName(), step, out)));
        String observation = "[evidence-navigation] " + nav.toolName() + " "
                + (out.ok() ? "ok" : "failed") + ": " + truncate(out.summary(), 180);
        return new EvidenceNavResult(out.ok(), observation);
    }

    private static ResolvedCall evidenceNavigationCall(String requested, int used) {
        String nav = normalizeEvidenceNavigation(requested, used);
        ObjectNode args = MAPPER.createObjectNode();
        if ("home".equals(nav) || "end".equals(nav)
                || "page_down".equals(nav) || "page_up".equals(nav)) {
            args.put("combo", nav);
            return new ResolvedCall("hotkey", args);
        }
        args.put("direction", nav);
        args.put("amount", 5);
        return new ResolvedCall("scroll", args);
    }

    private static String normalizeEvidenceNavigation(String requested, int used) {
        String nav = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        if ("up".equals(nav) || "down".equals(nav)
                || "left".equals(nav) || "right".equals(nav)
                || "home".equals(nav) || "end".equals(nav)
                || "page_down".equals(nav) || "page_up".equals(nav)) {
            return nav;
        }
        if ("pagedown".equals(nav) || "page down".equals(nav)) return "page_down";
        if ("pageup".equals(nav) || "page up".equals(nav)) return "page_up";
        if (used == 0) return "page_down";
        if (used == 1) return "page_up";
        return "home";
    }

    private List<LlmClient.Message> buildMessages(String userTask, List<HistoryTurn> history,
            String runningSummary, String runPlan) {
        List<LlmClient.Message> out = new ArrayList<>();
        out.add(LlmClient.Message.system(basePrompt));
        if (runPlan != null && !runPlan.isBlank()) {
            out.add(LlmClient.Message.user(
                    "[execution plan from supervisor — follow these steps; if a step blocks twice, switch to its recovery]\n"
                            + runPlan));
        }
        if (runningSummary != null && !runningSummary.isBlank()) {
            out.add(LlmClient.Message.user(
                    "[run summary so far — read this before deciding the next step]\n" + runningSummary));
        }
        byte[] live = tools.invoke("screenshot", MAPPER.createObjectNode()).screenshotPng();
        String task = "[task]\n" + userTask + "\n\n" + VISION_NOTE;
        if (live != null && live.length > 0) {
            out.add(visualMessage(task, live));
        } else {
            out.add(LlmClient.Message.user(task));
        }
        int total = history.size();
        for (int i = 0; i < total; i++) {
            HistoryTurn t = history.get(i);
            boolean recent = (i >= total - IMAGE_TAIL);
            if (t.assistantText() != null && !t.assistantText().isBlank()) {
                out.add(LlmClient.Message.assistant(t.assistantText()));
            }
            for (int k = 0; k < t.calls().size(); k++) {
                XmlToolFormat.ParsedCall pc = t.calls().get(k);
                Tools.Outcome o = t.outcomes().get(k);
                StringBuilder obs = new StringBuilder();
                if (FORMAT_FEEDBACK_CALL.equals(pc.name())) {
                    obs.append("[parser feedback]\n");
                } else {
                    obs.append("[tool result · ").append(pc.name()).append("]\n");
                }
                obs.append("ok=").append(o.ok()).append('\n')
                        .append("summary: ").append(o.summary()).append('\n');
                if (recent && o.screenshotPng() != null && o.screenshotPng().length > 0) {
                    if (o.ocrCropPng() != null && o.ocrCropPng().length > 0) {
                        obs.append("\n[ocr_numbered_boxes near your last action — FRESH for turn ").append(t.step())
                                .append(", box numbers reset to 1..N each turn] (image 3 = the annotated crop)\n")
                                .append(o.ocrBoxesJson() == null ? "[]" : o.ocrBoxesJson())
                                .append("\nIf one of the numbered green boxes above is the element you actually wanted, ")
                                .append("retry by emitting <tool name=\"click_box\"><args>{\"box\":N}</args></tool> ")
                                .append("with N taken from THIS turn's list — Java will click that box's exact center. ")
                                .append("Do not reuse box numbers from previous turns. ")
                                .append("If the target you want has no text in this list (graphical icon like trash/new-layer/eye), ")
                                .append("use raw click(x,y) instead.\n");
                    }
                    out.add(visualMessage(obs + "\n" + VISION_NOTE, o.screenshotPng(), o.ocrCropPng()));
                } else {
                    if (o.screenshotPng() != null && o.screenshotPng().length > 0) {
                        obs.append("[img elided · turn ").append(t.step()).append(" — re-screenshot if needed]");
                    }
                    out.add(LlmClient.Message.user(obs.toString()));
                }
            }
        }
        return out;
    }

    private static boolean isClickTool(String name) {
        return "click".equals(name) || "double_click".equals(name) || "right_click".equals(name);
    }

    private JsonNode applyGrounding(String runId, int step, XmlToolFormat.ParsedCall pc,
            JsonNode args, Consumer<Events.Event> emit) {
        if (!groundingEnabled || grounding == null) return args;
        if (!isClickTool(pc.name())) return args;
        String reason = pc.reason() == null ? "" : pc.reason().trim();
        if (reason.isEmpty()) return args;
        if (!(args instanceof ObjectNode)) return args;
        ObjectNode obj = (ObjectNode) args;
        long t0 = System.currentTimeMillis();
        byte[] shot;
        try {
            shot = tools.invoke("screenshot", MAPPER.createObjectNode()).screenshotPng();
        } catch (RuntimeException ex) {
            log.warn("[agent.grounding] could not capture screenshot: {}", ex.toString());
            return args;
        }
        if (shot == null || shot.length == 0) return args;
        int origX = obj.path("x").asInt(-1);
        int origY = obj.path("y").asInt(-1);
        if (origX < 0 || origY < 0) return args;
        Optional<Grounding.VerifyResult> result = grounding.verify(shot, origX, origY, reason);
        long dt = System.currentTimeMillis() - t0;
        if (result.isEmpty()) {
            emit.accept(Events.of(runId, "grounding_skipped",
                    "verifier returned no verdict",
                    Events.meta()
                            .put("iteration", step)
                            .put("tool", pc.name())
                            .put("reason", reason)
                            .put("elapsedMs", (int) dt)
                            .build()));
            return args;
        }
        Grounding.VerifyResult v = result.get();
        if (v.correct()) {
            emit.accept(Events.of(runId, "grounding_confirmed",
                    "verifier confirmed (" + origX + "," + origY + ") on target",
                    Events.meta()
                            .put("iteration", step)
                            .put("tool", pc.name())
                            .put("reason", reason)
                            .put("originalX", origX)
                            .put("originalY", origY)
                            .put("elapsedMs", (int) dt)
                            .build()));
            return obj;
        }
        if (v.x() < 0 || v.y() < 0) {
            emit.accept(Events.of(runId, "grounding_skipped",
                    "verifier said wrong but supplied no correction",
                    Events.meta()
                            .put("iteration", step)
                            .put("tool", pc.name())
                            .put("reason", reason)
                            .put("elapsedMs", (int) dt)
                            .build()));
            return obj;
        }
        obj.put("x", v.x());
        obj.put("y", v.y());
        emit.accept(Events.of(runId, "grounding_corrected",
                "verifier moved (" + origX + "," + origY + ") -> (" + v.x() + "," + v.y() + ")",
                Events.meta()
                        .put("iteration", step)
                        .put("tool", pc.name())
                        .put("reason", reason)
                        .put("originalX", origX)
                        .put("originalY", origY)
                        .put("groundedX", v.x())
                        .put("groundedY", v.y())
                        .put("elapsedMs", (int) dt)
                        .build()));
        return obj;
    }

    private String compactHistoryIfNeeded(String runId, int step, String userTask,
            List<HistoryTurn> history, String runningSummary, Consumer<Events.Event> emit) {
        if (step % SUMMARY_INTERVAL != 0 || history.size() <= SUMMARY_KEEP_TAIL) {
            return runningSummary == null ? "" : runningSummary;
        }
        bailIfCancelled();
        int evictCount = history.size() - SUMMARY_KEEP_TAIL;
        List<HistoryTurn> toEvict = new ArrayList<>(history.subList(0, evictCount));
        emit.accept(Events.of(runId, "summary_started",
                "Summarizing oldest " + evictCount + " turns and evicting from context",
                Events.meta()
                        .put("iteration", step)
                        .put("evictCount", evictCount)
                        .put("keepTail", SUMMARY_KEEP_TAIL)
                        .build()));

        String segment = null;
        String failReason = null;
        try {
            segment = summarizeRun(userTask, toEvict, runningSummary);
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            log.warn("[agent] summarize failed at step {}: {}", step, ex.toString());
            failReason = ex.getMessage();
        }

        String nextSummary = (segment != null && !segment.isBlank())
                ? segment
                : synthesizeFallbackSummary(toEvict, runningSummary);
        history.subList(0, evictCount).clear();

        if (segment != null && !segment.isBlank()) {
            emit.accept(Events.of(runId, "summary_completed",
                    "Evicted " + evictCount + " turns · running summary "
                            + nextSummary.length() + " chars",
                    Events.meta()
                            .put("iteration", step)
                            .put("evicted", evictCount)
                            .put("remainingHistory", history.size())
                            .put("chars", nextSummary.length())
                            .put("text", truncate(segment, 4000))
                            .build()));
        } else {
            emit.accept(Events.of(runId, "summary_failed",
                    (failReason == null
                            ? "Run summary returned empty text · evicted with synthetic fallback"
                            : "Run summary error · evicted with synthetic fallback: " + failReason),
                    Events.meta()
                            .put("iteration", step)
                            .put("evicted", evictCount)
                            .put("remainingHistory", history.size())
                            .put("chars", nextSummary.length())
                            .build()));
        }
        return nextSummary;
    }

    private static String synthesizeFallbackSummary(List<HistoryTurn> evicted, String prior) {
        StringBuilder sb = new StringBuilder();
        if (prior != null && !prior.isBlank()) {
            sb.append(prior.trim()).append(' ');
        }
        for (HistoryTurn t : evicted) {
            for (int k = 0; k < t.calls().size(); k++) {
                XmlToolFormat.ParsedCall pc = t.calls().get(k);
                Tools.Outcome o = t.outcomes().get(k);
                sb.append("[t").append(t.step()).append(' ')
                        .append(pc.name()).append(' ')
                        .append(o.ok() ? "ok" : "fail")
                        .append(": ").append(truncate(o.summary(), 100)).append("] ");
            }
        }
        String out = sb.toString().trim();
        if (out.length() > 900) out = out.substring(out.length() - 900);
        return out;
    }

    private String summarizeRun(String userTask, List<HistoryTurn> history, String prior) {
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(SUMMARIZER_SYSTEM));
        if (prior != null && !prior.isBlank()) {
            messages.add(LlmClient.Message.user("[prior running summary]\n" + prior));
        }
        messages.add(LlmClient.Message.user("[original user task]\n" + userTask));
        for (HistoryTurn t : history) {
            if (t.assistantText() != null && !t.assistantText().isBlank()) {
                messages.add(LlmClient.Message.assistant(t.assistantText()));
            }
            for (int k = 0; k < t.calls().size(); k++) {
                XmlToolFormat.ParsedCall pc = t.calls().get(k);
                Tools.Outcome o = t.outcomes().get(k);
                StringBuilder obs = new StringBuilder();
                if (FORMAT_FEEDBACK_CALL.equals(pc.name())) {
                    obs.append("[parser feedback · turn ").append(t.step()).append("]\n");
                } else {
                    obs.append("[tool result · ").append(pc.name())
                            .append(" · turn ").append(t.step()).append("]\n");
                }
                obs.append("ok=").append(o.ok()).append('\n')
                        .append("summary: ").append(o.summary()).append('\n');
                if (o.screenshotPng() != null && o.screenshotPng().length > 0) {
                    messages.add(LlmClient.Message.userImage(obs.toString(), o.screenshotPng()));
                } else {
                    messages.add(LlmClient.Message.user(obs.toString()));
                }
            }
        }
        LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, summarizerModel, messages,
                0.2, summarizerMaxTokens);
        String text = reply.text() == null ? "" : reply.text().trim();
        log.info("[agent.summary] model={} produced {} chars, total_tokens={}",
                summarizerModel, text.length(), reply.totalTokens());
        return text;
    }

    private static LlmClient.Message visualMessage(String text, byte[] screenshotPng) {
        return visualMessage(text, screenshotPng, null);
    }

    private static LlmClient.Message visualMessage(String text, byte[] screenshotPng, byte[] extra) {
        if (screenshotPng == null || screenshotPng.length == 0) {
            return LlmClient.Message.user(text);
        }
        byte[] grid = CoordinateGrid.forScreenshot(screenshotPng);
        List<byte[]> imgs = new ArrayList<>();
        imgs.add(screenshotPng);
        if (grid != null && grid.length > 0) imgs.add(grid);
        if (extra != null && extra.length > 0) imgs.add(extra);
        if (imgs.size() == 1) return LlmClient.Message.userImage(text, screenshotPng);
        return LlmClient.Message.userImages(text, imgs);
    }

    private static List<XmlToolFormat.ParsedCall> dedupAndCap(List<XmlToolFormat.ParsedCall> parsed) {
        List<XmlToolFormat.ParsedCall> out = new ArrayList<>();
        String prevName = null;
        String prevArgs = null;
        for (XmlToolFormat.ParsedCall p : parsed) {
            if (out.size() >= MAX_BATCH_PER_RESPONSE) break;
            String n = p.name();
            String a = p.argsJson() == null ? "" : p.argsJson();
            if (n != null && n.equals(prevName) && a.equals(prevArgs)) continue;
            prevName = n;
            prevArgs = a;
            out.add(p);
        }
        return out;
    }

    private static boolean hasSuccessfulWork(List<HistoryTurn> history,
            List<XmlToolFormat.ParsedCall> currentCalls,
            List<Tools.Outcome> currentOutcomes) {
        for (HistoryTurn t : history) {
            for (int i = 0; i < t.outcomes().size() && i < t.calls().size(); i++) {
                if (countsAsWork(t.calls().get(i).name(), t.outcomes().get(i))) return true;
            }
        }
        for (int i = 0; i < currentOutcomes.size() && i < currentCalls.size(); i++) {
            if (countsAsWork(currentCalls.get(i).name(), currentOutcomes.get(i))) return true;
        }
        return false;
    }

    private static boolean hasSuccessfulVisualWork(List<HistoryTurn> history,
            List<XmlToolFormat.ParsedCall> currentCalls,
            List<Tools.Outcome> currentOutcomes) {
        for (HistoryTurn t : history) {
            for (int i = 0; i < t.outcomes().size() && i < t.calls().size(); i++) {
                if (countsAsVisualWork(t.calls().get(i).name(), t.outcomes().get(i))) return true;
            }
        }
        for (int i = 0; i < currentOutcomes.size() && i < currentCalls.size(); i++) {
            if (countsAsVisualWork(currentCalls.get(i).name(), currentOutcomes.get(i))) return true;
        }
        return false;
    }

    private static boolean countsAsWork(String toolName, Tools.Outcome outcome) {
        if (outcome == null || !outcome.ok() || outcome.done()) return false;
        return !List.of("screenshot", "list_apps", "apps_catalog", "done", "fail", FORMAT_FEEDBACK_CALL).contains(toolName);
    }

    private static boolean countsAsVisualWork(String toolName, Tools.Outcome outcome) {
        if (outcome == null || !outcome.ok() || outcome.done() || hasNoVisibleChange(outcome)) return false;
        return List.of("launch_app",
                "click", "double_click", "right_click",
                "click_box", "click_target",
                "find_click", "find_and_click", "click_text",
                "menu_path", "menu_walk", "compound_click", "click_path",
                "type", "type_text", "hotkey", "key", "press_key", "enter",
                "escape", "scroll", "wait").contains(toolName);
    }

    /** Renders a {@link GoalContract} as a stable text block that can be
     *  injected into either planner or verifier prompts. Returns an empty
     *  string when the contract is empty so callers can use the result
     *  unconditionally. Format is intentionally compact and prose-free — the
     *  consumers must be able to read cardinality and spatial fields without
     *  natural-language reinterpretation. */
    private static String renderGoalContract(GoalContract contract) {
        if (contract == null || contract.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("[goal contract]\n");
        sb.append("Authoritative parse of the user goal. Treat each item as one\n");
        sb.append("acceptance state with the cardinality and spatial relation shown.\n");
        sb.append("Do NOT enumerate cardinality (e.g. do not produce one acceptance\n");
        sb.append("state per cell when cardinality is ANY_ONE).\n");
        for (GoalContract.ContractItem it : contract.items()) {
            sb.append("- id=").append(it.id())
                    .append(" cardinality=").append(it.cardinality().name());
            if (it.cardinality() == GoalContract.Cardinality.EXACT_N && it.exactN() > 0) {
                sb.append("(n=").append(it.exactN()).append(')');
            }
            if (it.spatial() != null) {
                sb.append(" spatial=").append(it.spatial().relation())
                        .append('(').append(it.spatial().targetRef()).append(')');
            }
            sb.append('\n').append("  description: ").append(it.description()).append('\n');
        }
        return sb.toString();
    }

    private static String renderParserContext(OmniParserClient.ParseResult parse) {
        if (parse == null || parse.elements() == null || parse.elements().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("[parser context]\n");
        sb.append("Fused OCR/icon elements from the same screenshot. Coordinates are bbox=[x1,y1,x2,y2]. ");
        sb.append("Advisory only: use this to write element_description with label/icon, bbox/region, and nearby elements. ");
        sb.append("Do not output parser ids or box ids.\n");
        int count = 0;
        for (OmniParserClient.Element e : parse.elements()) {
            if (e == null) continue;
            String content = e.content() == null ? "" : e.content().trim();
            String type = e.type() == null || e.type().isBlank() ? "element" : e.type().trim();
            sb.append("- ").append(type)
                    .append(" bbox=[")
                    .append(e.x1()).append(',').append(e.y1()).append(',')
                    .append(e.x2()).append(',').append(e.y2()).append(']')
                    .append(" center=(").append(e.centerX()).append(',').append(e.centerY()).append(')');
            if (e.interactivity()) sb.append(" interactive");
            if (!content.isBlank()) sb.append(" label=\"").append(truncate(content, 90)).append('"');
            sb.append('\n');
            count++;
            if (count >= 80 || sb.length() >= 6000) break;
        }
        int remaining = parse.elements().size() - count;
        if (remaining > 0) {
            sb.append("- ... ").append(remaining).append(" more elements omitted\n");
        }
        return sb.toString();
    }

    /** Structural classification of a checkpoint's text into one of a few kinds
     *  the verifier can ask a tighter question about. Pure regex on the
     *  checkpoint string — no LLM call, no goal-context lookup. Default kind is
     *  FREE_FORM, which falls through to the generic verifier prompt. */
    private enum CheckpointKind { FREE_FORM, FOCUS_IN, TEXT_IN, APP_OPEN, FILE_EXISTS }

    private static CheckpointKind classifyCheckpoint(String checkpoint) {
        if (checkpoint == null || checkpoint.isBlank()) return CheckpointKind.FREE_FORM;
        String t = checkpoint.toLowerCase(Locale.ROOT);
        // FILE_EXISTS first — file-related verbs are unambiguous and override visual hints.
        if (t.matches(".*\\b(file|document|folder|directory)\\b.*\\b(saved|created|exists|present|written)\\b.*")
                || t.matches(".*\\b(saved|created|exported|downloaded)\\b\\s+(file|to|as)\\b.*")) {
            return CheckpointKind.FILE_EXISTS;
        }
        // APP_OPEN — covers "X is launched/running/open/closed/minimized".
        if (t.matches(".*\\b(launched|running|open(ed)?|closed|minimi[sz]ed|in\\s+the\\s+foreground|active\\s+window)\\b.*")) {
            return CheckpointKind.APP_OPEN;
        }
        // TEXT_IN — "<text> is in/inside/within <structure>" or "<text> ... in cell/field/panel".
        if (t.matches(".*\\b(text|content|word|fact|value|name)\\b.*\\b(in(side)?|within|inside the|in the)\\b.*\\b(cell|field|panel|table|column|row|input|textbox|search\\s*bar|address\\s*bar)\\b.*")
                || t.matches(".*\\bis\\s+(present|visible|entered|typed)\\s+(in(side)?|within)\\b.*")) {
            return CheckpointKind.TEXT_IN;
        }
        // FOCUS_IN — "cursor / focus / caret is in/inside <X>".
        if (t.matches(".*\\b(cursor|caret|focus|selection)\\b.*\\b(is|positioned)\\b.*\\b(in(side)?|within|on)\\b.*")
                || t.matches(".*\\bfocus(ed)?\\s+(in(side)?|within|on)\\b.*")) {
            return CheckpointKind.FOCUS_IN;
        }
        return CheckpointKind.FREE_FORM;
    }

    /** Returns true when the active checkpoint text mentions a state class that the
     *  sandbox_evidence raw bundle can answer but current_state cannot — namely
     *  filesystem changes, clipboard contents, or process trees. For app-state
     *  checkpoints (open/closed/running/launched) current_state is sufficient and
     *  attaching the raw bundle wastes 5-10k tokens. Pure heuristic, conservative:
     *  any of these keywords flips the gate ON and we attach the bundle. */
    private static boolean checkpointMentionsSandboxOnlyChannels(String checkpoint) {
        if (checkpoint == null) return false;
        String t = checkpoint.toLowerCase();
        String[] needles = {
                "save", "saved", "write", "wrote", "written", "create file", "created",
                "delete", "deleted", "rename", "renamed", "copy file", "copied",
                "download", "downloaded", "upload", "uploaded", "export", "exported",
                "file ", "files ", "folder", "directory", "path",
                "clipboard", "copied to", "pasted",
                "process", "pid", "running process",
        };
        for (String n : needles) {
            if (t.contains(n)) return true;
        }
        return false;
    }

    private static boolean hasNoVisibleChange(Tools.Outcome outcome) {
        // visible_change/screen_delta_pct is unreliable on this sandbox (clicks
        // routinely report visible_change=false even when they did land). Treat
        // it as advisory telemetry only — never as a behavior gate. Authoritative
        // signals are tool ok=true and the verifier; not a pixel-diff threshold.
        return false;
    }

    private static boolean destructiveActionJustified(String checkpoint, String userTask) {
        String active = checkpoint == null ? "" : checkpoint.toLowerCase(Locale.ROOT);
        if (containsAny(active, DESTRUCTIVE_KEYWORDS)) return true;

        String user = userTask == null ? "" : userTask.toLowerCase(Locale.ROOT);
        return containsAny(user, DESTRUCTIVE_USER_GOAL_KEYWORDS)
                && containsAny(active, ABSENCE_STATE_KEYWORDS);
    }

    private static boolean containsAny(String text, java.util.List<String> needles) {
        if (text == null || text.isBlank()) return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void settleBeforeObservation(String runId, int step, Consumer<Events.Event> emit) {
        int ms = Math.max(0, observationSettleMs);
        if (ms <= 0) return;
        emit.accept(Events.of(runId, "observation_settle",
                "Waiting " + ms + "ms before screenshot so transient loading UI can settle",
                Events.meta()
                        .put("iteration", step)
                        .put("ms", ms)
                        .build()));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ix) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ix);
        }
        bailIfCancelled();
    }

    private static boolean shouldSettleBeforeNextObservation(List<ResolvedCall> calls,
            List<Tools.Outcome> outcomes) {
        if (calls == null || outcomes == null) return false;
        for (int i = 0; i < calls.size() && i < outcomes.size(); i++) {
            Tools.Outcome outcome = outcomes.get(i);
            if (outcome == null || !outcome.ok() || outcome.done()) continue;
            if (needsObservationSettle(calls.get(i).toolName())) return true;
        }
        return false;
    }

    private static boolean needsObservationSettle(String toolName) {
        return List.of("launch_app",
                "click", "double_click", "right_click",
                "click_box", "click_target",
                "find_click", "find_and_click", "click_text",
                "menu_path", "menu_walk", "compound_click", "click_path",
                "type", "type_text", "hotkey", "key", "press_key", "enter",
                "escape", "scroll").contains(toolName);
    }

    private Verification verifyActiveCheckpoint(String userTask, String checkpoint, byte[] shot) {
        return verifyActiveCheckpoint(null, null, userTask, checkpoint, shot, "");
    }

    private Verification verifyActiveCheckpoint(String runId, Consumer<Events.Event> emit,
            String userTask, String checkpoint, byte[] shot, String evidenceJson) {
        return verifyActiveCheckpoint(runId, emit, userTask, checkpoint, shot, evidenceJson, -1, -1L, "");
    }

    private Verification verifyActiveCheckpoint(String runId, Consumer<Events.Event> emit,
            String userTask, String checkpoint, byte[] shot, String evidenceJson,
            int taskSeq, long taskStartedTs) {
        return verifyActiveCheckpoint(runId, emit, userTask, checkpoint, shot, evidenceJson,
                taskSeq, taskStartedTs, "");
    }

    /**
     * Full diff-enriched checkpoint verifier.
     *
     * <p>Auto-injects:
     * <ul>
     *   <li>task_baseline screenshot (last shot before task started)</li>
     *   <li>task_current screenshot annotated with action point rings</li>
     *   <li>windows_diff text</li>
     *   <li>apps_installed_diff text</li>
     *   <li>task duration</li>
     * </ul>
     *
     * <p>On-demand tool calling is left as a TODO — see inline comment.
     */
    private Verification verifyActiveCheckpoint(String runId, Consumer<Events.Event> emit,
            String userTask, String checkpoint, byte[] shot, String evidenceJson,
            int taskSeq, long taskStartedTs, String lineageBlock) {
        if (shot == null || shot.length == 0) {
            return new Verification(false, "no checkpoint screenshot available");
        }
        String cacheKey = verdictCache.key(checkpoint == null ? "" : checkpoint, shot);
        Optional<VerdictCache.CachedVerdict> hit = verdictCache.get(cacheKey);
        if (hit.isPresent()) {
            VerdictCache.CachedVerdict entry = hit.get();
            if (emit != null && runId != null) {
                emit.accept(Events.of(runId, "checkpoint_cache_hit",
                        "Cached verdict reused for active checkpoint",
                        Events.meta()
                                .put("checkpoint", checkpoint == null ? "" : checkpoint)
                                .put("ok", entry.ok())
                                .put("reason", entry.reason())
                                .build()));
            }
            return new Verification(entry.ok(), entry.reason(), true);
        }

        // ----------------------------------------------------------------
        // Deterministic pre-flight: if the checkpoint is a state-fact about
        // an OS-level signal (process running / not running), call the
        // matching tool directly and short-circuit. Skips a 27k-token vision
        // call that Lite was choking on for "is X process running?" queries.
        // ----------------------------------------------------------------
        Verification deterministic = tryDeterministicVerify(runId, checkpoint, emit);
        if (deterministic != null) {
            verdictCache.put(cacheKey, deterministic.ok(), deterministic.reason());
            return deterministic;
        }

        // ----------------------------------------------------------------
        // Build diff-enriched context
        // ----------------------------------------------------------------
        long verifyTs = System.currentTimeMillis();
        long durationMs = taskStartedTs > 0 ? (verifyTs - taskStartedTs) : -1;

        // Annotate current screenshot with action point rings
        byte[] currentShot = shot;
        if (taskSeq > 0) {
            List<EvidenceStore.ActionPoint> pts = evidenceStore.getActionPoints(taskSeq);
            if (!pts.isEmpty()) {
                currentShot = PngAnnotator.annotate(shot, pts);
            }
        }

        // Find the baseline screenshot (last shot captured before task started).
        // On backtrack to a parent checkpoint, the parent's taskStartedTs may predate any
        // shot that's still in memory — the baseline will be null and the verifier sees
        // only the after-shot. Log it so backtrack symptoms are diagnosable.
        byte[] baselineShot = null;
        if (taskStartedTs > 0) {
            List<EvidenceStore.Shot> beforeShots = evidenceStore.getShotsInRange(0L, taskStartedTs - 1);
            if (!beforeShots.isEmpty()) {
                baselineShot = beforeShots.get(beforeShots.size() - 1).png();
            }
        }
        if (baselineShot == null) {
            log.warn("[verifier] no baseline shot for taskSeq={} (taskStartedTs={} verifyTs={}); "
                    + "likely a backtrack or pre-first-shot verify",
                    taskSeq, taskStartedTs, verifyTs);
        }

        // Compute window and app diffs.
        // Distinguish three states so the verifier cannot conflate them:
        //   - "no change"           — diff was actually computed and is empty
        //   - "evidence_unavailable" — sandbox /eyes/evidence returned no data this turn
        //   - "no_baseline"          — run-start snapshot was missing; nothing to diff against
        // The previous default of "no change" silently degraded fetch failures into a
        // false "no state changed" signal, so the verifier read "windows_diff: no change"
        // and rejected the (correctly closed) app. Honesty about channel availability
        // forces the verifier to either call a tool or refuse to claim windows_diff proves
        // anything when the diff was never actually computed.
        String windowsDiffText;
        String appsDiffText;
        String freshEvidenceJson = "";
        try {
            freshEvidenceJson = tools.evidenceBundle(null, null);
        } catch (RuntimeException ex) {
            log.warn("[verifier] evidence fetch failed: {}", ex.toString());
        }
        boolean evidenceFetchOk = freshEvidenceJson != null && !freshEvidenceJson.isBlank();
        boolean baselineOk = runBaselineEvidenceJson != null && !runBaselineEvidenceJson.isBlank();
        List<String> evidenceUsed = new ArrayList<>();
        Set<String> usableAuthoritative = new LinkedHashSet<>();
        // Build a state-now snapshot from the fresh evidence. This answers "is X currently
        // present?" — different from windows_diff which answers "did X change?". For
        // state-fact checkpoints ("LibreOffice is closed") that started with X already
        // absent, current_state is the correct channel; windows_diff would say "no change"
        // and the verifier was conflating that with "goal not achieved".
        String currentStateText;
        boolean currentStateUsable = false;
        if (evidenceFetchOk) {
            try {
                com.fasterxml.jackson.databind.JsonNode freshNode = parseEvidenceJson(freshEvidenceJson);
                currentStateText = renderCurrentState(freshNode);
                currentStateUsable = true;
                usableAuthoritative.add("current_state");
                evidenceUsed.add("current_state");
            } catch (RuntimeException ex) {
                log.warn("[verifier] current_state render failed: {}", ex.toString());
                currentStateText = "(parse_error: " + ex.getClass().getSimpleName() + ")";
            }
        } else {
            currentStateText = "(unavailable: sandbox /eyes/evidence returned no data this turn)";
        }
        if (!evidenceFetchOk) {
            windowsDiffText = "(evidence_unavailable: sandbox /eyes/evidence returned no data this turn)";
            appsDiffText = "(evidence_unavailable: sandbox /eyes/evidence returned no data this turn)";
        } else if (!baselineOk) {
            windowsDiffText = "(no_baseline: run-start snapshot missing; nothing to diff against)";
            appsDiffText = "(no_baseline: run-start snapshot missing; nothing to diff against)";
        } else {
            try {
                com.fasterxml.jackson.databind.JsonNode baselineNode = parseEvidenceJson(runBaselineEvidenceJson);
                com.fasterxml.jackson.databind.JsonNode freshNode = parseEvidenceJson(freshEvidenceJson);
                EvidenceDiff.WindowsDiff wDiff = EvidenceDiff.windowsDiff(
                        baselineNode.path("running_windows"),
                        freshNode.path("running_windows"));
                windowsDiffText = wDiff.renderForPrompt();
                EvidenceDiff.AppsDiff aDiff = EvidenceDiff.appsInstalledDiff(
                        baselineNode.path("apps"),
                        freshNode.path("apps"));
                appsDiffText = aDiff.renderForPrompt();
                evidenceUsed.add("windows_diff");
                evidenceUsed.add("apps_diff");
                usableAuthoritative.add("windows_diff");
                usableAuthoritative.add("apps_installed_diff");
            } catch (RuntimeException ex) {
                log.warn("[verifier] diff computation failed: {}", ex.toString());
                windowsDiffText = "(diff_error: " + ex.getClass().getSimpleName() + ")";
                appsDiffText = "(diff_error: " + ex.getClass().getSimpleName() + ")";
            }
        }
        if (evidenceFetchOk) usableAuthoritative.add("sandbox_evidence");

        // pixel_diff: third authoritative channel. Catches intra-window state changes that
        // the OS-level windows/apps lists do not see (file dialogs, document edits, focus
        // moves, etc). Only usable when we actually have both before- and after-shots.
        String pixelDiffText;
        boolean pixelDiffUsable = false;
        if (baselineShot != null && baselineShot.length > 0 && shot != null && shot.length > 0) {
            try {
                long pixelStart = System.currentTimeMillis();
                PixelDiff.Result pd = PixelDiff.diff(baselineShot, shot);
                long pixelMs = System.currentTimeMillis() - pixelStart;
                pixelDiffText = pd.renderForPrompt();
                pixelDiffUsable = true;
                usableAuthoritative.add("pixel_diff");
                evidenceUsed.add("pixel_diff");
                if (emit != null && runId != null) {
                    emit.accept(Events.of(runId, "pixel_diff_computed",
                            pd.boxes().size() + " region(s) changed (" + pd.changedPixels() + " px) in " + pixelMs + "ms",
                            Events.meta()
                                    .put("regions", pd.boxes().size())
                                    .put("changedPixels", pd.changedPixels())
                                    .put("elapsedMs", pixelMs)
                                    .build()));
                }
            } catch (RuntimeException ex) {
                log.warn("[verifier] pixel diff failed: {}", ex.toString());
                pixelDiffText = "(diff_error: " + ex.getClass().getSimpleName() + ")";
            }
        } else {
            pixelDiffText = "(unavailable: missing before-shot or after-shot)";
        }

        if (emit != null && runId != null) {
            emit.accept(Events.of(runId, "verifier_diff_status",
                    (evidenceFetchOk && baselineOk) ? "diff_computed" : "diff_unavailable",
                    Events.meta()
                            .put("evidenceFetchOk", evidenceFetchOk)
                            .put("baselineOk", baselineOk)
                            .put("pixelDiffUsable", pixelDiffUsable)
                            .put("usableChannels", String.join(",", usableAuthoritative))
                            .build()));
        }

        // ----------------------------------------------------------------
        // Build prompt — composition order is intentional:
        //   1. Active TODO (the question)
        //   2. AUTHORITATIVE state channels (windows_diff, apps_installed_diff, sandbox)
        //   3. Supplementary visual (screenshots are attached as images, not in text)
        //   4. Task context (user goal + duration)
        //   5. Question, anchored to authoritative channels
        // Every channel block is always emitted with a non-empty label (even when "no
        // change") so the verifier cannot hallucinate "this channel is unavailable".
        // ----------------------------------------------------------------
        StringBuilder promptSb = new StringBuilder();
        // Lineage is now structural: parents live in the checklist's parentId graph and
        // are rendered as a separate "[lineage]" block by RecursiveTodoState.activeLineage().
        // The leaf checkpoint text contains only the active claim — never a "X for: X for: ..."
        // chain — so the verifier sees one assertion to verify and (when present) the path
        // of parent goals that justified subdividing into this leaf.
        promptSb.append("[active TODO]\n").append(checkpoint == null ? "" : checkpoint.trim());
        if (lineageBlock != null && !lineageBlock.isBlank()) {
            promptSb.append("\n\n").append(lineageBlock.trim());
        }
        promptSb.append("\n\n=== AUTHORITATIVE STATE CHANNELS ===");
        // current_state leads — it answers "is X present NOW?" which is the right question
        // for state-fact goals like "X is closed". Transition diffs (windows_diff, etc)
        // come AFTER and only answer "did X change?".
        promptSb.append("\n[current_state] (")
                .append(currentStateUsable ? "USABLE; snapshot of currently running windows + installed apps."
                                             + " Answers \"is X present right now?\""
                                          : "UNUSABLE THIS TURN — do not cite as authoritative")
                .append(")\n")
                .append(currentStateText == null || currentStateText.isBlank() ? "(empty)" : currentStateText);
        promptSb.append("\n\n=== TRANSITION CHANNELS (did the state CHANGE since task start) ===");
        boolean windowsDiffUsable = usableAuthoritative.contains("windows_diff");
        boolean appsDiffUsable = usableAuthoritative.contains("apps_installed_diff");
        boolean sandboxUsable = usableAuthoritative.contains("sandbox_evidence")
                || (evidenceJson != null && !evidenceJson.isBlank());
        promptSb.append("\n[windows_diff] (")
                .append(windowsDiffUsable ? "USABLE; reflects actual window list deltas"
                                          : "UNUSABLE THIS TURN — do not cite as authoritative")
                .append(")\n")
                .append(windowsDiffText == null || windowsDiffText.isBlank() ? "no change" : windowsDiffText);
        promptSb.append("\n\n[apps_installed_diff] (")
                .append(appsDiffUsable ? "USABLE; reflects /usr/share/applications deltas"
                                       : "UNUSABLE THIS TURN — do not cite as authoritative")
                .append(")\n")
                .append(appsDiffText == null || appsDiffText.isBlank() ? "no change" : appsDiffText);
        promptSb.append("\n\n[pixel_diff] (")
                .append(pixelDiffUsable ? "USABLE; bounding boxes around regions where the after-shot"
                                          + " differs from the before-shot at the pixel level"
                                       : "UNUSABLE THIS TURN — do not cite as authoritative")
                .append(")\n")
                .append(pixelDiffText == null || pixelDiffText.isBlank() ? "no change" : pixelDiffText);
        // sandbox_evidence is the raw evidence-bundle JSON. The window/app fields are
        // already extracted into current_state above, so attaching the full bundle
        // duplicates ~5-10k tokens for every verifier call. Only include when the
        // checkpoint text suggests filesystem / clipboard / process-tree relevance.
        boolean sandboxRelevant = checkpoint != null
                && checkpointMentionsSandboxOnlyChannels(checkpoint);
        if (sandboxUsable && sandboxRelevant && evidenceJson != null && !evidenceJson.isBlank()) {
            promptSb.append("\n\n[sandbox_evidence] (USABLE; raw running_windows + dirs + clipboard)\n")
                    .append(evidenceJson.trim());
            evidenceUsed.add("sandbox_evidence");
        } else if (sandboxUsable) {
            promptSb.append("\n\n[sandbox_evidence] (omitted — current_state already covers")
                    .append(" running_windows and apps; raw bundle attached only when the")
                    .append(" checkpoint asks about files, clipboard, or processes)");
        } else {
            promptSb.append("\n\n[sandbox_evidence] (UNUSABLE THIS TURN — do not cite as authoritative)");
        }
        promptSb.append("\n\n=== SUPPLEMENTARY VISUAL ===");
        promptSb.append("\n[screenshot] one after-shot is attached as an image.");
        promptSb.append(" It has numbered+faded rings showing the temporal order of agent clicks.");
        promptSb.append(" The before-shot is intentionally NOT attached — pixel_diff (above) already");
        promptSb.append(" describes the regions that changed; sending two images doubles the prompt cost");
        promptSb.append(" without adding signal the verifier uses.");
        promptSb.append("\n\n=== TASK CONTEXT ===");
        promptSb.append("\n[user goal]\n").append(userTask == null ? "" : userTask);
        if (runContract != null && !runContract.isEmpty()) {
            // Authoritative parsed contract. Surfaces cardinality and spatial
            // relations explicitly so the verifier doesn't have to re-interpret
            // the prose. The SCOPE rule still applies (judge only the active
            // TODO), but when the active TODO maps to a contract item, the
            // verifier consults cardinality/spatial here instead of guessing.
            promptSb.append("\n\n").append(renderGoalContract(runContract).trim());
        }
        if (durationMs >= 0) {
            promptSb.append("\n[task_duration_ms] ").append(durationMs);
        }
        promptSb.append("\n\n[question]\nBased on the AUTHORITATIVE state channels, does the current");
        promptSb.append(" state satisfy the active TODO? Cite the specific channel(s) that prove your");
        promptSb.append(" answer. If a deterministic channel can answer (windows for app open/closed,");
        promptSb.append(" sandbox dirs for files saved, clipboard for copied text), use it — do not");
        promptSb.append(" rely on the screenshot when an authoritative channel is present.");

        // Per-kind narrowing — when the active TODO has a recognizable structural
        // shape, append a tighter sub-question that forces the verifier to check
        // the specific spatial / containment relation instead of accepting "all
        // referenced things are visible somewhere on screen". The kind is derived
        // from the checkpoint text only, no goal-context heuristics.
        CheckpointKind kind = classifyCheckpoint(checkpoint);
        switch (kind) {
            case FOCUS_IN -> promptSb.append("\n\n[narrowing — FOCUS_IN]\nThe TODO asserts that focus")
                    .append(" or the cursor is inside a specific structure. Verify only that geometric")
                    .append(" relation: is the cursor / caret / selection clearly inside the named")
                    .append(" structure? A blinking caret outside that structure is a REJECT even if")
                    .append(" the structure is visible elsewhere.");
            case TEXT_IN -> promptSb.append("\n\n[narrowing — TEXT_IN]\nThe TODO asserts that some text")
                    .append(" is present INSIDE a specific structure (cell, field, panel). Verify the")
                    .append(" text is geometrically inside that structure's bounds — text appearing")
                    .append(" above/below/beside the structure is a REJECT, even if both are visible")
                    .append(" on the same screenshot.");
            case APP_OPEN -> promptSb.append("\n\n[narrowing — APP_OPEN]\nThe TODO asserts an app/window")
                    .append(" is in a specific runtime state (open / closed / launched / minimized).")
                    .append(" current_state.running_windows is the authoritative channel; do not")
                    .append(" accept screenshot-only evidence when current_state is USABLE.");
            case FILE_EXISTS -> promptSb.append("\n\n[narrowing — FILE_EXISTS]\nThe TODO asserts a file")
                    .append(" exists / was saved / was created. sandbox_evidence (or the ls / file_stat")
                    .append(" tools) is authoritative; the screenshot is supplementary at best.");
            case FREE_FORM -> { /* no narrowing */ }
        }

        String verifyPromptText = promptSb.toString();
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(VERIFIER_SYSTEM_WITH_TOOLS));
        // Always send only the after-shot. The before-shot adds ~3k tokens per
        // verifier call without adding signal — pixel_diff (text bounding boxes)
        // already encodes what changed between the two frames.
        messages.add(LlmClient.Message.userImage(verifyPromptText, currentShot));

        try {
            int budget = 5;
            int toolsUsed = 0;
            for (int turn = 0; turn <= budget; turn++) {
                LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, checkpointVerifierModel,
                        messages, 0.0, checkpointVerifierMaxTokens, LlmClient.CallRole.VERIFIER);
                String text = reply.text() == null ? "" : reply.text().trim();
                JsonNode node = tryParseJsonObject(text);
                if (node == null) {
                    Verification v = new Verification(false, "verifier emitted non-JSON output", false,
                            Verification.Status.INCONCLUSIVE_AFTER_BUDGET, List.copyOf(evidenceUsed), "");
                    // Do NOT cache INCONCLUSIVE — caching it poisons subsequent
                    // identical-state checks (cache hit → re-served broken
                    // verdict → blame ledger saturates).
                    return v;
                }
                if (node.path("final").asBoolean(false)) {
                    // The model decides — only the model. Once it emits a FINAL, the runtime
                    // accepts the verdict verbatim. No string-matching of the reason, no
                    // channel-citation gates, no "is this evidence good enough" judgments by
                    // the runtime. The whole job of the runtime is to compose the inputs
                    // (annotated screenshots, current_state, transitions, sandbox evidence,
                    // tool results) and give the LLM space to reason. The verdict is the
                    // LLM's responsibility alone.
                    //
                    // Strict ok parse — same principle as the completion verifier
                    // (parseVerification). If the model emits {final:true, reason:"..."}
                    // without a boolean ok, do NOT default to false; that fabricates a
                    // REJECT verdict from a missing field. Return INCONCLUSIVE so the
                    // caller can choose to re-ask, fall back to checklist evidence, or
                    // surface the gap to the planner.
                    appendVerifierEvidence(node, evidenceUsed);
                    String statusText = node.path("status").asText("");
                    boolean needsEvidence = "needs_evidence".equals(statusText.trim().toLowerCase(Locale.ROOT))
                            || node.path("needs_evidence").asBoolean(false);
                    String reason = node.path("reason").asText("(no reason)");
                    if (needsEvidence) {
                        return new Verification(false, reason, false,
                                Verification.Status.NEEDS_EVIDENCE,
                                List.copyOf(evidenceUsed),
                                node.path("navigation").asText(""));
                    }
                    if (!node.has("ok") || !node.path("ok").isBoolean()) {
                        String partialReason = node.path("reason").asText("");
                        Verification v = new Verification(false,
                                "verifier final reply missing boolean ok field; reason="
                                        + truncate(partialReason, 180), false,
                                Verification.Status.INCONCLUSIVE_AFTER_BUDGET,
                                List.copyOf(evidenceUsed), "");
                        // Inconclusive: skip cache (don't poison future hits).
                        return v;
                    }
                    boolean ok = node.path("ok").asBoolean(false);
                    Verification v = new Verification(ok, reason, false,
                            ok ? Verification.Status.ACCEPT : Verification.Status.REJECT,
                            List.copyOf(evidenceUsed), "");
                    verdictCache.put(cacheKey, v.ok(), v.reason());
                    return v;
                }
                if (turn == budget) {
                    Verification v = new Verification(false,
                            "verifier did not finalize within " + budget + " tool turns", false,
                            Verification.Status.INCONCLUSIVE_AFTER_BUDGET,
                            List.copyOf(evidenceUsed), "");
                    // Inconclusive: skip cache.
                    return v;
                }
                // Tool-call branch. The LLM may use either {"tool": "<name>", "args": {...}}
                // (our schema) or OpenAI-style {"name": "<name>", "arguments": {...}}. Accept
                // both. If neither shape resolves to a valid tool name, the turn is malformed —
                // do NOT call invokeVerifierTool (which would emit "unknown tool: " into the
                // evidence list and corrupt the channels-checked summary). Instead, log the raw
                // text so the failure mode is diagnosable, append a precise nudge, and continue.
                String tool = node.path("tool").asText("");
                if (tool.isBlank()) tool = node.path("name").asText("");
                JsonNode args = node.has("args") ? node.path("args") : node.path("arguments");
                if (tool == null || tool.isBlank()) {
                    log.warn("[verifier] turn {} emitted malformed JSON (no 'final':true, no 'tool'/'name'): {}",
                            turn, truncate(text, 240));
                    if (emit != null && runId != null) {
                        emit.accept(Events.of(runId, "verifier_malformed_turn",
                                "verifier emitted neither FINAL nor a valid TOOL CALL",
                                Events.meta()
                                        .put("turn", turn)
                                        .put("rawPreview", truncate(text, 200))
                                        .build()));
                    }
                    messages.add(LlmClient.Message.assistant(text));
                    messages.add(LlmClient.Message.user(
                            "[malformed] Your previous response had neither \"final\": true nor a"
                            + " \"tool\" name from the allowed list (clipboard_text, ls, file_stat,"
                            + " read_file, find_recent, process_running). Emit one of the two shapes"
                            + " exactly as documented in the system prompt. JSON only, no prose."));
                    continue;
                }
                String result = invokeVerifierTool(tool, args);
                toolsUsed++;
                evidenceUsed.add("tool:" + tool);
                messages.add(LlmClient.Message.assistant(text));
                messages.add(LlmClient.Message.user("[tool_result] " + tool + ": " + truncate(result, 4000)));
                if (emit != null && runId != null) {
                    emit.accept(Events.of(runId, "verifier_tool_call",
                            tool + " -> " + truncate(result, 200),
                            Events.meta().put("turn", turn).put("tool", tool).build()));
                }
            }
            // unreachable — loop always returns inside
            return new Verification(false, "verifier loop exited unexpectedly", false,
                    Verification.Status.INCONCLUSIVE_AFTER_BUDGET, List.copyOf(evidenceUsed), "");
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            log.warn("[agent] checkpoint verifier failed: {}", ex.toString());
            return new Verification(false, "checkpoint verifier error: " + ex.getMessage());
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode parseEvidenceJson(String json) {
        if (json == null || json.isBlank()) return MAPPER.createObjectNode();
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            return MAPPER.createObjectNode();
        }
    }

    /** Render the state-now snapshot of the sandbox: which windows are currently open and
     *  what apps are installed. This answers "is X present right now?" — distinct from
     *  windows_diff which answers "did X change between baseline and now?". For state-fact
     *  checkpoints where the goal state was already true at task start, current_state is
     *  the only correct channel; windows_diff = "no change" is uninformative. */
    private static String firstNonBlank(com.fasterxml.jackson.databind.JsonNode... nodes) {
        for (com.fasterxml.jackson.databind.JsonNode n : nodes) {
            if (n == null || n.isMissingNode() || n.isNull()) continue;
            String s = n.asText("");
            if (s != null && !s.isBlank()) return s;
        }
        return "";
    }

    private static String renderCurrentState(com.fasterxml.jackson.databind.JsonNode evidence) {
        StringBuilder sb = new StringBuilder();
        com.fasterxml.jackson.databind.JsonNode windows = evidence.path("running_windows");
        if (windows.isMissingNode() || windows.isNull()) {
            // The sandbox response did not include this field at all. Treat as channel
            // unavailable — different from "the OS reports zero windows". The verifier
            // must NOT read a missing field as proof of absence.
            sb.append("running_windows: (channel_missing — the sandbox response did not"
                    + " include this field; do NOT treat this as proof that no windows are open)");
        } else if (!windows.isArray()) {
            sb.append("running_windows: (channel_malformed — value was not a JSON array;"
                    + " do NOT treat this as proof of any window state)");
        } else if (windows.isEmpty()) {
            // Genuine empty array. The OS window manager affirmatively reports zero
            // top-level windows. This still does not prove "nothing on the screen" —
            // the screenshot may show app UI the WM does not report (containerized
            // setups, embedded webviews, secondary X displays).
            sb.append("running_windows: 0 reported (OS window-manager affirmatively reports zero"
                    + " top-level windows; check the screenshot for any visible UI before concluding)");
        } else {
            sb.append("running_windows (").append(windows.size()).append("):");
            for (com.fasterxml.jackson.databind.JsonNode w : windows) {
                // Sandbox /eyes/observe schema actually uses "app" as the class field,
                // not "wm_class" (which is always null). Fall back to other plausible
                // names for forward-compat across sandbox builds.
                String klass = firstNonBlank(w.path("app"), w.path("wm_class"),
                        w.path("class"), w.path("proc"));
                String title = firstNonBlank(w.path("title"), w.path("name"));
                sb.append("\n  - ");
                if (!klass.isBlank() && !title.isBlank()) {
                    sb.append(klass).append(" — ").append(title);
                } else if (!klass.isBlank()) {
                    sb.append(klass);
                } else if (!title.isBlank()) {
                    sb.append(title);
                } else {
                    sb.append("(unidentified window — class and title both empty)");
                }
            }
        }
        com.fasterxml.jackson.databind.JsonNode apps = evidence.path("apps");
        if (apps.isArray() && !apps.isEmpty()) {
            // Keep current_state focused on live OS state. The app catalog can be
            // queried with list_apps when needed; dumping 60 desktop files every
            // turn burns tokens on most tasks.
            int total = apps.size();
            int cap = Math.min(total, 12);
            sb.append("\napps_installed (").append(total)
              .append(", showing first ").append(cap)
              .append(" — use list_apps with query before launch_app if target is missing):");
            int i = 0;
            for (com.fasterxml.jackson.databind.JsonNode a : apps) {
                if (i++ >= cap) break;
                String name = firstNonBlank(a.path("name"), a.path("title"), a.path("id"));
                String df = firstNonBlank(a.path("desktop_file"), a.path("path"));
                String exec = firstNonBlank(a.path("exec"), a.path("command"));
                sb.append("\n  - ");
                if (!name.isBlank()) sb.append(name);
                if (!df.isBlank()) sb.append(" | desktop_file=").append(df);
                else if (!exec.isBlank()) sb.append(" | exec=").append(exec);
            }
            if (total > cap) {
                sb.append("\n  … ").append(total - cap)
                  .append(" more (use list_apps with a query to find a specific app)");
            }
        }
        return sb.toString();
    }

    /**
     * Static gates the verifier must satisfy before its FINAL verdict is accepted.
     * Returns {@code null} when both gates pass; otherwise returns a nudge string the
     * runtime appends to the conversation so the model can re-emit a compliant verdict.
     *
     * <p>Gate 1: at least one verification tool was called this turn-loop (toolsUsed &gt; 0),
     *           OR at least 2 distinct evidence channels are cited in the FINAL.
     * <p>Gate 2: the {@code reason} field is at least 8 words / 60 chars of concrete prose.
     *
     * <p>The point: an LLM verifier that snap-judges from a screenshot in 1 turn with
     * one-line reason is exactly the failure mode this method blocks. Forcing either a
     * deterministic tool call or multi-channel citation prevents single-channel mistakes
     * (e.g. mistaking the LibreOffice Start Center for an open Writer window).
     */

    private static final String VERIFIER_SYSTEM_WITH_TOOLS = """
            You verify one active TODO for a desktop agent. Inputs are organized in two tiers:

            Craft bar: the user sees only the final result. Be the careful, honest
            verifier who would refuse to ship a sloppy outcome and who refuses to
            reject a clean one on a hunch. Cite the exact channel(s) that prove
            your verdict. No vibes-based rejections, no rubber-stamp accepts.

            SCOPE — judge ONLY the active TODO. The [user goal] block is provided for
            context (so you understand intent) but is NEVER a basis for rejection.
            If a user goal lists multiple steps and the active TODO is one of them,
            you must verify only that step. Other parts of the user goal are
            someone else's checkpoint and will be verified on their own turn.
            A FINAL with ok=false whose reason cites "the OTHER parts of the user
            goal aren't done yet" is a defect — set ok=true if the active TODO is
            satisfied, regardless of what else remains in the broader plan.


            INPUT CHANNELS — each answers a DIFFERENT question. Pick the right one.

            current_state.running_windows
              answers: "is APPLICATION X currently open as a top-level window?"
              does NOT answer: what is displayed inside an app, what page a browser shows,
              what file is open in an editor, what content is visible in any panel.
              Empty list means the OS-level window manager reports zero top-level windows.
              It does NOT mean nothing is on the screen.

            current_state.apps
              answers: "is application X installed on this system?"
              does NOT answer: anything about runtime, content, or UI state.

            sandbox_evidence
              answers: raw OS-level facts — running processes, directory contents,
              clipboard text. Use for file-existence and process-state questions.

            windows_diff / apps_installed_diff
              answer: "did the OS-level window list / app list CHANGE between task start
              and now?". "no change" does NOT mean the goal is unsatisfied — it means the
              state didn't transition, which is fine if the goal was already true at start.

            pixel_diff
              answers: "what visual regions of the screen changed between before-shot and
              after-shot?". Big regions == big visual change. Empty == nothing visually
              changed. Does NOT identify what the regions show.

            screenshots (before-shot, after-shot images attached as images)
              answer: "what is visible on the screen right now?". This is the ONLY channel
              for questions about page content, document text, image presence, UI element
              visibility, dialog contents, focus state, or anything inside an application.
              Window-manager channels cannot see inside windows.

            ROUTING RULES:
            - "Is X application open / closed / running?" -> current_state.running_windows
            - "Is X installed?" -> current_state.apps
            - "Is file Y saved / present?" -> sandbox_evidence (dirs)
            - "Is text Z in the clipboard?" -> sandbox_evidence (clipboard)
            - "Is image / text / UI element visible on the page / in the app / on screen?"
              -> screenshots. The screenshot is authoritative for visual content.
              Do NOT use current_state.running_windows to answer page-content questions.
            - "Did the screen change after the action?" -> pixel_diff + screenshots

            REJECT GUARD — before emitting a FINAL with ok=false, you MUST have either:
              (a) called at least one verification tool this turn (process_running / ls /
                  file_stat / clipboard_text / find_recent / read_file), OR
              (b) cross-checked at least two independent channels (e.g. current_state +
                  screenshots, or windows_diff + pixel_diff) that AGREE on the rejection.
            Sandbox channels can be stale, malformed, or temporarily unavailable
            (channel_missing / channel_malformed labels in the prompt mean exactly that).
            A reject from a single ambiguous channel is a weak verdict — corroborate first.
            If your reject would rest on "current_state shows no running windows" and the
            screenshot clearly shows app UI, call process_running before rejecting; the OS
            window-manager query may have failed silently.

            SUPPLEMENTARY VISUAL:
              - before-shot and after-shot images (after has numbered+faded rings showing click order)
              These confirm UI state but are easily misread (similar logos, similar layouts).

            Three output shapes — pick exactly one per turn:
            1) FINAL: {"final": true,
                       "reason": "<full chain of reasoning>",
                       "evidence_cited": ["current_state", "windows_diff", "apps_installed_diff",
                                          "sandbox_evidence", "pixel_diff", "screenshots",
                                          "tool:<name>"],
                       "ok": true|false,
                       "obsolete": true|false (optional, only when ok:true)}
            2) NEEDS_EVIDENCE: {"final":true,"status":"needs_evidence",
                       "reason":"specific missing visual evidence",
                       "evidence_cited":["screenshots"],
                       "navigation":"down|up|home|end|page_down|page_up"}
            3) TOOL CALL: {"tool": "<name>", "args": {...}}  — runtime will run it and feed result back.
            Use NEEDS_EVIDENCE only when required evidence is likely off-screen or hidden
            behind a scrollable surface. Off-screen is unknown, not false.

            OBSOLETE VERDICT (use sparingly):
            Set "obsolete": true when the active TODO names a TRANSIENT prerequisite
            state (e.g. "the startup wizard window is visible", "the file-pick dialog
            is open") that the world has already moved past — and the user's larger
            goal that this prerequisite was meant to enable is either already true or
            still reachable without it. Examples:
              - TODO: "Impress startup wizard is visible" but the editor is already
                open on a blank slide → ok:true, obsolete:true.
              - TODO: "Save dialog is open" but the file is already saved (title bar
                no longer dirty) → ok:true, obsolete:true.
            DO NOT mark obsolete just because the prerequisite is hard to reach. Only
            mark obsolete when the world's CURRENT state already serves the goal that
            the prerequisite was a means to. The runtime treats obsolete as ACCEPT for
            control-flow but logs the verdict separately.

            CRITICAL — emit `reason` BEFORE `ok` in the JSON, exactly as shown above.
            Your `ok` value MUST be the logical consequence of the `reason` you just wrote:
              - if your reason concludes the TODO is satisfied/met/true → ok:true
              - if your reason concludes the TODO is unmet/false → ok:false
            Do not commit to a verdict and then write a reason that contradicts it. The verdict is
            the LAST thing you decide, after walking the evidence. We have observed self-contradicting
            replies of the form `{"ok":false, "reason":"...therefore the TODO is satisfied"}` — that
            is a defect in your output, the runtime now treats your `reason` as authoritative for
            consistency checking and your `ok` field is what it acts on, so reason and ok must agree.

            Tools available for sharper grounding (<=5 turns total):
              clipboard_text, ls, file_stat, read_file, find_recent, process_running.
              Use them when the channels in the prompt are ambiguous or you need stronger proof.

            How to write the FINAL reason:
            - Walk through the channels you actually consulted, in order.
            - State what each channel shows.
            - State the conclusion that follows from those observations.
            - Identify which checkpoint phrasing question this is (state-now vs transition).
            - Then, AFTER the reason is fully written, set `ok` to match the conclusion you just stated.
            The runtime accepts your verdict verbatim — the quality of your reasoning is on you.

            Available tools (<=5 per verification):
            - clipboard_text     -> no args
            - ls                 -> {"path": "<dir>"} or {"dir": "<dir>"}
            - file_stat          -> {"path": "<absolute path>"}
            - read_file          -> {"path": "<absolute path>"}
            - find_recent        -> {"dir": "<dir>", "since_ms": <epoch ms>}
            - process_running    -> {"name": "<process or window class substring>"}

            Accept when ANY channel proves the TODO. Reject when visible evidence contradicts it.
            Use NEEDS_EVIDENCE when the current viewport cannot decide.
            Output JSON only. No prose, no markdown.
            """;

    /**
     * Recovery actions are universally allowed even when their checkpoint_id
     * does not match the active one. They exist to ESCAPE a stuck or tangled
     * checkpoint, so requiring them to belong to the very checkpoint they're
     * recovering from would block the recovery path itself.
     *
     * Whitelist:
     *   - subdivide_checkpoint, skip_checkpoint  meta-actions on the checklist
     *   - verify_checkpoint                     by definition targets the
     *     active checkpoint — no other reason to call it; blocking it on
     *     checkpoint_id mismatch is always a false positive
     *   - done, fail                            terminal actions; checkpoint
     *     guard has no business gating completion
     *   - hotkey "ctrl+z" / "ctrl+y" / "cmd+z"  universal undo / redo
     *   - scroll / page navigation keys         evidence navigation; these reveal
     *     hidden UI state and are not checkpoint mutations
     *   - hotkey "return" / "enter"             completion gestures (path-tool
     *     close, dialog OK, drop-down commit) used by every interactive app
     *   - modified_click with ctrl/cmd modifier delete-anchor / deselect in
     *     path tools, layers panel, etc. — typically a deliberate recovery
     */
    private boolean isRecoveryAction(SeeActPlanner.Step step) {
        String act = step.action();
        if (act == null) return false;
        if (isNavigationAction(step)) return true;
        if ("subdivide_checkpoint".equals(act) || "skip_checkpoint".equals(act)) return true;
        if ("verify_checkpoint".equals(act)) return true;
        if ("done".equals(act) || "fail".equals(act)) return true;
        if ("hotkey".equals(act)) {
            String combo = step.combo();
            if (combo == null) return false;
            String c = combo.toLowerCase();
            boolean undoLike = (c.contains("ctrl") || c.contains("cmd")) && (c.contains("z") || c.contains("y"));
            boolean completionKey = "return".equals(c) || "enter".equals(c);
            return undoLike || completionKey;
        }
        // modified_click is rarer than click; ctrl-click in path tools deletes
        // an anchor, in layer panels deselects, in selection tools subtracts.
        // All recovery semantics. Allow through; if the LLM misuses it, the
        // damage is bounded (one click) and visible in the next screenshot.
        return "modified_click".equals(act);
    }

    private static boolean isNavigationAction(SeeActPlanner.Step step) {
        if (step == null || step.action() == null) return false;
        String act = step.action();
        if ("scroll".equals(act)) return true;
        if (!"hotkey".equals(act) && !"key".equals(act) && !"press_key".equals(act)) return false;
        return isNavigationCombo(step.combo());
    }

    private static boolean isNavigationCombo(String combo) {
        if (combo == null) return false;
        String c = combo.trim().toLowerCase(Locale.ROOT);
        return "home".equals(c) || "end".equals(c)
                || "page_down".equals(c) || "page_up".equals(c)
                || "pagedown".equals(c) || "pageup".equals(c)
                || "page down".equals(c) || "page up".equals(c)
                || "ctrl+home".equals(c) || "ctrl+end".equals(c)
                || "cmd+home".equals(c) || "cmd+end".equals(c);
    }

    /**
     * Pre-flight: scan a batched actions[] array and return human-readable
     * issues for any subaction that lacks the required target fields. Empty
     * list = batch is well-formed. Caller fails the whole batch if non-empty
     * so the planner sees the validation error in the next iteration's
     * observation and can re-emit cleanly without burning a dispatch round.
     */
    private static final java.util.Set<String> CLICK_LIKE = java.util.Set.of(
            "click", "double_click", "right_click", "modified_click", "long_click",
            "click_box", "click_target", "modified_scroll");
    private static final java.util.Set<String> DRAG_LIKE = java.util.Set.of(
            "drag", "drag_to", "drag_between", "drag_from_to", "drag_drop",
            "modified_drag", "drag_hold_observe_release", "drag_box", "left_click_drag");
    private static final java.util.Set<String> POINTS_LIKE = java.util.Set.of(
            "mouse_path", "freehand_path", "lasso_path", "scrub_slider", "compound_click", "click_path");

    private List<String> validateBatchSubactions(JsonNode batch) {
        List<String> issues = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            JsonNode s = batch.get(i);
            String act = s.path("action").asText("").trim();
            if (act.isEmpty()) {
                issues.add("[" + i + "] missing action field");
                continue;
            }
            if ("type_text".equals(act) || "type".equals(act)) {
                String text = s.path("text").asText(
                        s.path("text_to_type").asText(
                        s.path("textToType").asText(
                        s.path("value").asText(""))));
                if (text.isBlank()) {
                    issues.add("[" + i + "] '" + act + "' missing text");
                }
            } else if (CLICK_LIKE.contains(act)) {
                boolean hasXY = s.has("x") && s.has("y") && !s.get("x").isNull() && !s.get("y").isNull();
                String desc = s.path("element_description").asText(
                        s.path("description").asText(s.path("target").asText("")));
                if (!hasXY && desc.isBlank()) {
                    issues.add("[" + i + "] '" + act + "' missing both (x,y) and element_description");
                }
            } else if (DRAG_LIKE.contains(act)) {
                boolean hasFromXY = s.has("from_x") && s.has("from_y");
                boolean hasToXY = s.has("to_x") && s.has("to_y");
                String fromDesc = s.path("from_description").asText(s.path("from").asText(""));
                String toDesc = s.path("to_description").asText(s.path("to").asText(""));
                boolean hasDescPair = !fromDesc.isBlank() && !toDesc.isBlank();
                if (!(hasFromXY && hasToXY) && !hasDescPair) {
                    issues.add("[" + i + "] '" + act
                            + "' needs (from_x,y AND to_x,y) OR (from_description AND to_description)");
                }
            } else if (POINTS_LIKE.contains(act)) {
                JsonNode pts = s.path(act.equals("compound_click") || act.equals("click_path")
                        ? "path" : "points");
                if (!pts.isArray() || pts.size() < 2) {
                    issues.add("[" + i + "] '" + act + "' needs a non-empty points/path array (>=2)");
                }
            }
            // hotkey, key_down, key_up, wait, screenshot, nudge, launch_app,
            // activate_window, list_apps, write_file, read_file, done, fail —
            // either no target or target is in another field that the executor
            // validates itself. Pass through.
        }
        return issues;
    }

    /**
     * Run a waypoint's pre or post action array. Each entry is a tool dispatch
     * spec like {"action":"modified_scroll","x":400,"y":380,"amount":5,"keys":["ctrl"]}
     * — same shape as a batch subaction. Used for the designer flow: zoom in
     * before grounding/clicking the anchor, zoom out after.
     */
    private void runWaypointSidecar(JsonNode wp, String which, String wpId,
                                    String runId, int step, String checkpointId,
                                    Consumer<Events.Event> emit) {
        JsonNode arr = wp.path(which);
        if (!arr.isArray() || arr.size() == 0) return;
        for (int i = 0; i < arr.size(); i++) {
            JsonNode sa = arr.get(i);
            String saAction = sa.path("action").asText("").trim();
            if (saAction.isEmpty()) continue;
            String note = sa.path("note").asText(sa.path("comment").asText(""));
            emit.accept(Events.of(runId, "waypoint_sidecar",
                    "[" + wpId + "] " + which + "[" + i + "] " + saAction
                            + (note.isBlank() ? "" : " — " + note),
                    Events.meta()
                            .put("iteration", step)
                            .put("waypointId", wpId)
                            .put("phase", which)
                            .put("index", i)
                            .put("action", saAction)
                            .put("checkpointId", checkpointId)
                            .build()));
            try {
                tools.invoke(saAction, sa);
            } catch (Exception ex) {
                log.warn("[waypoint sidecar] {} '{}' threw: {}", which, saAction, ex.getMessage());
            }
        }
    }

    /**
     * If a batched subaction has element_description / from_description /
     * to_description but lacks x/y, ground via UGround and return a mutable
     * copy with the coords filled in. Pure pass-through if x/y already set.
     * type_text is deliberately not grounded: it types at current focus, and
     * the planner must put a click before it when focus is not already correct.
     */
    private JsonNode groundBatchSubaction(JsonNode sub, String subAction, byte[] rawPng) {
        if ("type_text".equals(subAction) || "type".equals(subAction)) {
            ObjectNode patched = sub.deepCopy();
            String text = patched.path("text").asText(
                    patched.path("text_to_type").asText(
                    patched.path("textToType").asText(
                    patched.path("value").asText(""))));
            if (!text.isBlank()) {
                patched.put("text", text);
            }
            return patched;
        }
        if (uGround == null || !uGround.enabled() || rawPng == null) return sub;
        boolean isClickLike = subAction.equals("click") || subAction.equals("double_click")
                || subAction.equals("right_click") || subAction.equals("modified_click")
                || subAction.equals("long_click") || subAction.equals("modified_scroll");
        boolean isDragLike = subAction.equals("drag") || subAction.equals("drag_to")
                || subAction.equals("drag_between") || subAction.equals("drag_from_to")
                || subAction.equals("drag_drop") || subAction.equals("modified_drag")
                || subAction.equals("drag_hold_observe_release");
        if (!isClickLike && !isDragLike) return sub;
        ObjectNode patched = sub.deepCopy();
        if (isClickLike && (!patched.has("x") || !patched.has("y"))) {
            String desc = patched.path("element_description").asText(
                    patched.path("description").asText(
                    patched.path("target").asText("")));
            if (!desc.isBlank()) {
                try {
                    UGroundClient.GroundedPoint p = uGround.locate(rawPng, desc);
                    patched.put("x", p.x()); patched.put("y", p.y());
                } catch (Exception ex) {
                    log.warn("[batch] ground click '{}' failed: {}", truncate(desc, 80), ex.getMessage());
                }
            }
        }
        if (isDragLike && (!patched.has("from_x") || !patched.has("from_y"))) {
            String fromDesc = patched.path("from_description").asText(
                    patched.path("from").asText(""));
            if (!fromDesc.isBlank()) {
                try {
                    UGroundClient.GroundedPoint p = uGround.locate(rawPng, fromDesc);
                    patched.put("from_x", p.x()); patched.put("from_y", p.y());
                } catch (Exception ex) {
                    log.warn("[batch] ground from '{}' failed: {}", truncate(fromDesc, 80), ex.getMessage());
                }
            }
        }
        if (isDragLike && (!patched.has("to_x") || !patched.has("to_y"))) {
            String toDesc = patched.path("to_description").asText(
                    patched.path("to").asText(""));
            if (!toDesc.isBlank()) {
                try {
                    UGroundClient.GroundedPoint p = uGround.locate(rawPng, toDesc);
                    patched.put("to_x", p.x()); patched.put("to_y", p.y());
                } catch (Exception ex) {
                    log.warn("[batch] ground to '{}' failed: {}", truncate(toDesc, 80), ex.getMessage());
                }
            }
        }
        return patched;
    }

    private Verification verifyVisualCompletion(String runId, String userTask, Consumer<Events.Event> emit) {
        byte[] shot = tools.invoke("screenshot", MAPPER.createObjectNode()).screenshotPng();
        if (shot == null || shot.length == 0) {
            return new Verification(false, "no final screenshot available");
        }
        emit.accept(Events.of(runId, "completion_check_started", "Verifying final screen against user request",
                Map.of("bytes", String.valueOf(shot.length))));

        // Pull the same evidence bundle the per-checkpoint verifier uses so the completion
        // check is grounded in OS-level state, not screenshot pixels alone. Without this,
        // the completion verifier saw the final screen (Chrome) and rejected on visual
        // entailment ("doesn't show LibreOffice closing") even though the goal state
        // (LibreOffice not running) was satisfied.
        String currentStateText = "(unavailable)";
        String windowsDiffText = "(unavailable)";
        String appsDiffText = "(unavailable)";
        String freshEvidenceJson = "";
        try {
            freshEvidenceJson = tools.evidenceBundle(null, null);
        } catch (RuntimeException ex) {
            log.warn("[completion-verifier] evidence fetch failed: {}", ex.toString());
        }
        boolean evidenceOk = freshEvidenceJson != null && !freshEvidenceJson.isBlank();
        boolean baselineOk = runBaselineEvidenceJson != null && !runBaselineEvidenceJson.isBlank();
        if (evidenceOk) {
            try {
                com.fasterxml.jackson.databind.JsonNode freshNode = parseEvidenceJson(freshEvidenceJson);
                currentStateText = renderCurrentState(freshNode);
                if (baselineOk) {
                    com.fasterxml.jackson.databind.JsonNode baselineNode = parseEvidenceJson(runBaselineEvidenceJson);
                    EvidenceDiff.WindowsDiff wDiff = EvidenceDiff.windowsDiff(
                            baselineNode.path("running_windows"), freshNode.path("running_windows"));
                    windowsDiffText = wDiff.renderForPrompt();
                    EvidenceDiff.AppsDiff aDiff = EvidenceDiff.appsInstalledDiff(
                            baselineNode.path("apps"), freshNode.path("apps"));
                    appsDiffText = aDiff.renderForPrompt();
                }
            } catch (RuntimeException ex) {
                log.warn("[completion-verifier] evidence parse failed: {}", ex.toString());
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("[original_user_request]\n").append(userTask == null ? "" : userTask);
        prompt.append("\n\n=== AUTHORITATIVE STATE CHANNELS ===");
        prompt.append("\n[current_state] (state-now snapshot — answers \"is X present right now?\")\n")
                .append(currentStateText == null || currentStateText.isBlank() ? "(empty)" : currentStateText);
        prompt.append("\n\n[windows_diff] (transition since task start)\n")
                .append(windowsDiffText == null || windowsDiffText.isBlank() ? "no change" : windowsDiffText);
        prompt.append("\n\n[apps_installed_diff] (transition since task start)\n")
                .append(appsDiffText == null || appsDiffText.isBlank() ? "no change" : appsDiffText);
        if (evidenceOk) {
            prompt.append("\n\n[sandbox_evidence] (raw running_windows + dirs + clipboard)\n")
                    .append(freshEvidenceJson.trim());
        }
        prompt.append("\n\n=== SUPPLEMENTARY VISUAL ===\n[final_screenshot] attached as image.");
        prompt.append("\n\n[question]\nDoes the WORLD STATE described above satisfy the user's request?"
                + " For state-fact requests (\"close X\", \"open Y\", \"create Z\"), check current_state"
                + " first — if the snapshot shows the goal state, the request is satisfied even if no"
                + " transition is visible in the screenshot. The screenshot proves UI state; the OS-level"
                + " channels prove process/file state. Cite which channel(s) prove your verdict.");

        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system("""
                        You verify whether the user's original request is satisfied by the final WORLD STATE.
                        Inputs are organized as:
                          AUTHORITATIVE STATE CHANNELS — current_state (state-now), windows_diff (transition),
                          apps_installed_diff (transition), sandbox_evidence (raw OS state)
                          SUPPLEMENTARY VISUAL — final_screenshot
                        For state-fact goals (close X / open Y / file exists Z), the snapshot under
                        current_state is the right channel. windows_diff = "no change" does NOT mean the
                        goal is unsatisfied — it means the state did not transition, which is fine if the
                        goal state was already true at task start.
                        Do NOT reason about agent intent or whether you saw the action happen. Verify the
                        end state, not the transition.
                        Output only JSON in this exact order:
                          {"reason":"channel-cited reasoning, written first",
                           "ok":true|false}
                        Your `ok` value MUST agree with the conclusion of the `reason` you just wrote.
                        Do not commit to a verdict before reasoning. Write the reason, walk the
                        channels you cited, draw the conclusion, then set `ok` to match it.
                        """),
                visualMessage(prompt.toString(), shot));
        try {
            LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, checkpointVerifierModel,
                    messages, 0.0, checkpointVerifierMaxTokens, LlmClient.CallRole.BINARY_VERIFIER);
            Verification first = parseVerification(reply.text());
            if (!first.isInconclusive()) return first;

            // One re-ask with an explicit shape nudge. The model often produced
            // correct reasoning in prose; it just forgot to wrap it. Re-ask with
            // the prior reply attached so it can lift its own answer into JSON
            // shape rather than re-reason from scratch.
            String prior = reply.text() == null ? "" : reply.text().trim();
            List<LlmClient.Message> retry = List.of(
                    LlmClient.Message.system("""
                            Reply with JSON only, exactly:
                              {"reason":"short reason", "ok":true|false}
                            Emit `reason` BEFORE `ok` so your verdict is a function of your reasoning.
                            Do not output prose, do not output markdown, do not wrap in code fences.
                            Read your previous reply (provided below) and translate its conclusion into
                            this JSON shape. If your previous reply concluded the request was satisfied,
                            return ok:true. If it concluded the request was not satisfied, return ok:false.
                            """),
                    LlmClient.Message.user("[previous_reply_to_translate_into_json]\n"
                            + truncate(prior, 1200)
                            + "\n\n[required_output]\n{\"ok\":..., \"reason\":\"...\"}"));
            LlmClient.Reply reply2 = llm.chat(LlmClient.Lane.TEXT, checkpointVerifierModel,
                    retry, 0.0, 256, LlmClient.CallRole.BINARY_VERIFIER);
            Verification second = parseVerification(reply2.text());
            if (!second.isInconclusive()) return second;
            return Verification.inconclusive("verifier produced unparseable replies twice; "
                    + "first=" + truncate(prior, 200));
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            log.warn("[agent] completion verifier failed: {}", ex.toString());
            return new Verification(false, "completion verifier error: " + ex.getMessage());
        }
    }

    static Verification parseVerification(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = MAPPER.readTree(text.substring(start, end + 1));
                // NEEDS_EVIDENCE — verifier cannot decide because required
                // evidence is off-screen (scrollable surface or below fold).
                // Schema-only signal: when status=="needs_evidence" the
                // runtime triggers performEvidenceNavigation (bounded scroll
                // + re-verify) instead of blame-and-reject. Recognised
                // BEFORE the ok-path so a verifier that emits both can't
                // accidentally trip a hard reject.
                String statusText = node.path("status").asText("").trim().toLowerCase(Locale.ROOT);
                if ("needs_evidence".equals(statusText)) {
                    String reason = truncate(node.path("reason").asText("evidence off-screen"), 240);
                    String nav = node.path("navigation").asText("").trim().toLowerCase(Locale.ROOT);
                    return new Verification(false, reason, false,
                            Verification.Status.NEEDS_EVIDENCE,
                            java.util.List.of(), nav);
                }
                if (node.has("ok") && node.path("ok").isBoolean()) {
                    String reason = truncate(node.path("reason").asText("no verifier reason"), 240);
                    // OBSOLETE — world moved past this prerequisite checkpoint.
                    // Verifier emits {"ok": true, "obsolete": true, "reason": "..."}
                    // when the named state is no longer reachable but the underlying
                    // goal is already achievable or remains achievable without it.
                    if (node.path("obsolete").asBoolean(false)) {
                        return Verification.obsolete(reason);
                    }
                    return new Verification(node.path("ok").asBoolean(false), reason);
                }
            } catch (Exception ignored) {
                // fall through to inconclusive — runtime decides next step
            }
        }
        return Verification.inconclusive(text.isBlank()
                ? "empty verifier response"
                : "verifier reply not parseable: " + truncate(text, 200));
    }

    private static String noToolFeedback(String assistantText) {
        String last = assistantText == null || assistantText.isBlank()
                ? "(empty response)" : assistantText.strip();
        return NO_TOOL_FEEDBACK
                + "\n\nYOUR FAILED RESPONSE (not executed):\n<last_response>\n"
                + truncate(last, 900)
                + "\n</last_response>\n\nOutput a COMPLETE tools block now. Do not output only </tools>.\n"
                + "File-task shape:\n"
                + "<tools>\n"
                + "  <tool name=\"write_file\">\n"
                + "    <reason>save requested artifact</reason>\n"
                + "    <args>{\"path\":\"/workspace/agent-demo/result.md\",\"content\":\"# Result\\n...\"}</args>\n"
                + "  </tool>\n"
                + "  <tool name=\"done\">\n"
                + "    <reason>finish after saving</reason>\n"
                + "    <args>{\"summary\":\"Saved /workspace/agent-demo/result.md\"}</args>\n"
                + "  </tool>\n"
                + "</tools>\n\nClick shape:\n"
                + "<tools>\n"
                + "  <tool name=\"click\">\n"
                + "    <reason>execute the action now</reason>\n"
                + "    <args>{\"x\": 33, \"y\": 531}</args>\n"
                + "  </tool>\n"
                + "</tools>\n\nAllowed tools only: screenshot, launch_app, list_apps, click, compound_click, type, hotkey, "
                + "scroll, wait, write_file, read_file, done, fail. "
                + "No python. No selenium. No shell. No prose-only reply.";
    }

    private static JsonNode parseArgs(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception ex) {
            return MAPPER.createObjectNode();
        }
    }

    private static HistoryTurn formatFeedbackTurn(int inner, String assistantText, String feedback) {
        return new HistoryTurn(inner, assistantText,
                List.of(new XmlToolFormat.ParsedCall(FORMAT_FEEDBACK_CALL, "{}", "missing executable <tools> block")),
                List.of(Tools.Outcome.fail(feedback)));
    }

    static void bailIfCancelled() {
        Thread current = Thread.currentThread();
        boolean interrupted = current.isInterrupted();
        boolean tokenCancelled = currentCancelCheck().getAsBoolean();
        if (interrupted || tokenCancelled) {
            log.warn("[agent.cancel] refusing to continue thread={} interrupted={} tokenCancelled={}",
                    current.getName(), interrupted, tokenCancelled);
            throw new RuntimeException(new InterruptedException("agent cancelled"));
        }
    }

    private static BooleanSupplier currentCancelCheck() {
        BooleanSupplier check = CANCEL_CHECK.get();
        return check == null ? NEVER_CANCELLED : check;
    }

    private record HistoryTurn(int step, String assistantText,
            List<XmlToolFormat.ParsedCall> calls, List<Tools.Outcome> outcomes) {}

    record Verification(boolean ok, String reason, boolean fromCache,
            Verification.Status status, java.util.List<String> evidenceUsed,
            String navigation) {
        enum Status { ACCEPT, REJECT, OBSOLETE, NEEDS_EVIDENCE, INCONCLUSIVE_AFTER_BUDGET }
        Verification(boolean ok, String reason) {
            this(ok, reason, false,
                    ok ? Status.ACCEPT : Status.REJECT,
                    java.util.List.of(), "");
        }
        Verification(boolean ok, String reason, boolean fromCache) {
            this(ok, reason, fromCache,
                    ok ? Status.ACCEPT : Status.REJECT,
                    java.util.List.of(), "");
        }
        static Verification inconclusive(String reason) {
            return new Verification(false, reason, false,
                    Status.INCONCLUSIVE_AFTER_BUDGET, java.util.List.of(), "");
        }
        /** World moved past this checkpoint — the prerequisite state never
         *  appeared (or already disappeared) but the goal it served is
         *  already achievable. Treat as ACCEPT for control-flow but tag the
         *  status so the trace shows it wasn't a literal pass. */
        static Verification obsolete(String reason) {
            return new Verification(true, "obsolete: " + reason, false,
                    Status.OBSOLETE, java.util.List.of(), "");
        }
        boolean isInconclusive() { return status == Status.INCONCLUSIVE_AFTER_BUDGET; }
        boolean isObsolete()     { return status == Status.OBSOLETE; }
        boolean needsEvidence()  { return status == Status.NEEDS_EVIDENCE; }
    }

    private static JsonNode tryParseJsonObject(String text) {
        if (text == null) return null;
        String t = text.trim();
        int s = t.indexOf('{'), e = t.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            JsonNode n = MAPPER.readTree(t.substring(s, e + 1));
            return n.isObject() ? n : null;
        } catch (Exception ex) { return null; }
    }

    private static final Set<String> PROCESS_QUERY_STOP_WORDS = Set.of(
            "a", "an", "the", "new", "blank", "initial", "current",
            "application", "process", "app", "window", "windows", "document",
            "presentation", "slide", "workspace", "desktop", "screen",
            "active", "visible", "foreground", "focused", "focus",
            "running", "launched", "open", "opened", "closed",
            "shown", "displayed", "within", "inside", "primary");

    private static final Set<String> KNOWN_PROCESS_TOKENS = Set.of(
            "libreoffice", "soffice", "impress", "calc", "writer", "draw",
            "math", "gimp", "firefox", "chrome", "chromium", "terminal",
            "nautilus", "files");

    private static final List<String> UI_ELEMENT_CHECKPOINT_TERMS = List.of(
            "text box", "textbox", "title box", "subtitle", "placeholder",
            "body text", "main body", "button", "field", "cursor", "caret",
            "selection", "menu", "panel", "sidebar", "toolbar", "cell",
            "row", "column", "canvas", "image", "shape", "object");

    private static String normalizeVerifierProcessQuery(String raw) {
        if (raw == null) return "";
        String cleaned = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (cleaned.isBlank()) return "";

        List<String> kept = new ArrayList<>();
        for (String token : cleaned.split("\\s+")) {
            if (!token.isBlank() && !PROCESS_QUERY_STOP_WORDS.contains(token)) kept.add(token);
        }
        if (kept.isEmpty()) return "";

        if (kept.contains("impress")) return "impress";
        if (kept.contains("calc")) return "calc";
        if (kept.contains("writer")) return "writer";
        if (kept.contains("draw")) return "draw";
        if (kept.contains("math")) return "math";
        if (kept.contains("gimp")) return "gimp";
        if (kept.contains("firefox")) return "firefox";
        if (kept.contains("chrome")) return "chrome";
        if (kept.contains("chromium")) return "chromium";
        if (kept.contains("terminal")) return "terminal";
        if (kept.contains("nautilus") || kept.contains("files")) return "files";
        if (kept.contains("soffice") || kept.contains("libreoffice")) return "libreoffice";

        return String.join(" ", kept);
    }

    private static String normalizedMatchText(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static boolean containsNormalizedTerm(String normalizedText, String normalizedTerm) {
        if (normalizedText == null || normalizedText.isBlank()
                || normalizedTerm == null || normalizedTerm.isBlank()) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedTerm + " ");
    }

    private static boolean containsKnownProcessToken(String raw) {
        String normalized = normalizedMatchText(raw);
        if (normalized.isBlank()) return false;
        for (String token : KNOWN_PROCESS_TOKENS) {
            if (containsNormalizedTerm(normalized, token)) return true;
        }
        return false;
    }

    private static boolean containsUiElementCheckpointTerm(String raw) {
        String normalized = normalizedMatchText(raw);
        if (normalized.isBlank()) return false;
        for (String term : UI_ELEMENT_CHECKPOINT_TERMS) {
            if (containsNormalizedTerm(normalized, normalizedMatchText(term))) return true;
        }
        return false;
    }

    private static boolean shouldRunDeterministicProcessCheck(String checkpoint, String rawName) {
        String name = normalizedMatchText(rawName);
        if (name.isBlank()) return false;

        boolean knownSubject = containsKnownProcessToken(rawName);
        boolean knownCheckpoint = containsKnownProcessToken(checkpoint);
        boolean uiElementCheckpoint = containsUiElementCheckpointTerm(checkpoint);

        // Never turn widget/layout facts into process queries:
        // "main title text box is visible" must be judged by visual/evidence
        // verification, not process_running("text box").
        if (uiElementCheckpoint && !(knownSubject || knownCheckpoint)) return false;

        String c = normalizedMatchText(checkpoint);
        boolean explicitProcessNoun = containsNormalizedTerm(c, "process")
                || containsNormalizedTerm(c, "application")
                || containsNormalizedTerm(c, "app");
        boolean explicitWindowNoun = containsNormalizedTerm(c, "window")
                || containsNormalizedTerm(c, "windows");

        if (explicitProcessNoun) return true;
        if (explicitWindowNoun) return knownSubject || knownCheckpoint;
        return knownSubject || knownCheckpoint;
    }

    private static void appendJsonScalarText(JsonNode node, StringBuilder sb) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isValueNode()) {
            String v = node.asText("");
            if (!v.isBlank()) sb.append(' ').append(v);
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) appendJsonScalarText(child, sb);
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> appendJsonScalarText(e.getValue(), sb));
        }
    }

    private static boolean processEvidenceMatchesQuery(String normalizedQuery, JsonNode evidence) {
        String query = normalizeVerifierProcessQuery(normalizedQuery);
        if (query.isBlank()) return false;

        StringBuilder raw = new StringBuilder();
        appendJsonScalarText(evidence, raw);
        String haystack = normalizedMatchText(raw.toString());
        if (haystack.isBlank()) return false;

        String compactHaystack = haystack.replace(" ", "");
        String compactQuery = query.replace(" ", "");
        if (!compactQuery.isBlank()
                && (haystack.contains(query) || compactHaystack.contains(compactQuery))) {
            return true;
        }

        for (String token : query.split("\\s+")) {
            if (!token.isBlank() && !haystack.contains(token)) return false;
        }
        return true;
    }

    /** Deterministic pre-flight verifier for state-fact checkpoints.
     *
     * <p>For checkpoints that are pure OS-level facts ("X is/is not running",
     * "X is closed", "X process is launched") we don't need a 27k-token
     * vision LLM call — we just call the {@code process_running} tool and
     * compare the result to the expected polarity. Returns:
     * <ul>
     *   <li>non-null Verification when the checkpoint matched a known pattern
     *       — caller skips the LLM path and short-circuits.</li>
     *   <li>null when the pattern doesn't match — caller falls through to the
     *       full LLM verifier.</li>
     * </ul>
     *
     * <p>Patterns recognised (case-insensitive):
     * <pre>
     *   "X process is not running"      -> expect not-running
     *   "X is closed"                   -> expect not-running
     *   "no longer visible"             -> expect not-running
     *   "X is running"                  -> expect running
     *   "X is open" / "is launched"     -> expect running
     *   "X application is launched and visible" -> expect running
     * </pre>
     */
    private Verification tryDeterministicVerify(String runId, String checkpoint,
            Consumer<Events.Event> emit) {
        if (checkpoint == null || checkpoint.isBlank()) return null;
        String c = checkpoint.toLowerCase();
        if (!looksLikeProcessRuntimeCheckpoint(c)) return null;

        // Pattern 1: "X is/are not running" / "X process not running" / "no longer running"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?:the\\s+)?(?:([a-z][a-z0-9_\\-\\.]+(?:\\s+[a-z][a-z0-9_\\-\\.]+)?)\\s+)?(?:application|process|app|window)?\\s*"
                + "(?:is|are)?\\s*(?:no\\s+longer\\s+(?:running|visible|open)|not\\s+running|closed|terminated|gone)"
        ).matcher(c);
        if (m.find()) {
            String name = m.group(1) == null ? "" : m.group(1).trim();
            // Pull a substring before "is" to use as the name if regex didn't capture
            if (name.isEmpty()) {
                int idxIs = c.indexOf(" is ");
                if (idxIs > 0) name = c.substring(0, idxIs).replaceAll("[^a-z0-9 \\-_]", "").trim();
            }
            // Strip leading articles
            name = name.replaceFirst("^(the\\s+|libreoffice\\s+)*", "").trim();
            if (!name.isEmpty()) {
                return runProcessRunningCheck(runId, checkpoint, name, false, emit);
            }
        }

        // Pattern 2: "X is/are running" / "X is open" / "is launched and visible"
        m = java.util.regex.Pattern.compile(
                "([a-z][a-z0-9_\\-\\.]+(?:\\s+[a-z][a-z0-9_\\-\\.]+)?)\\s+(?:application|process|app|window)?\\s*"
                + "(?:is|are)\\s+(?:launched|running|open(?:ed)?|visible|active)"
        ).matcher(c);
        if (m.find()) {
            String name = m.group(1).trim().replaceFirst("^(the\\s+|libreoffice\\s+)*", "").trim();
            if (!name.isEmpty()) {
                return runProcessRunningCheck(runId, checkpoint, name, true, emit);
            }
        }

        return null;
    }

    private static boolean looksLikeProcessRuntimeCheckpoint(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        boolean runtimeVerb = t.matches(".*\\b(running|launched|open|opened|closed|terminated|gone|active|foreground)\\b.*")
                || t.contains("no longer running")
                || t.contains("not running");
        boolean runtimeNoun = t.matches(".*\\b(application|process|app|window)\\b.*");
        boolean knownApp = t.matches(".*\\b(libreoffice|impress|calc|writer|draw|gimp|firefox|chrome|chromium|terminal|files|nautilus|soffice)\\b.*");

        // "visible" alone is often a widget/layout fact: "text box is visible",
        // "button is visible", "slide is visible". Only let visibility trigger
        // deterministic process checks when it is explicitly about an app/window.
        if (t.matches(".*\\bvisible\\b.*") && !(runtimeNoun || knownApp)) return false;

        return runtimeVerb && (runtimeNoun || knownApp);
    }

    /** Helper: call process_running tool, compare to expected polarity, return Verification. */
    private Verification runProcessRunningCheck(String runId, String checkpoint,
            String name, boolean expectRunning, Consumer<Events.Event> emit) {
        if (!shouldRunDeterministicProcessCheck(checkpoint, name)) {
            log.info("[verifier.deterministic] skipped process_running for UI/non-process checkpoint subject '{}'",
                    name);
            return null;
        }
        String normalizedName = normalizeVerifierProcessQuery(name);
        if (normalizedName.isBlank()) {
            log.info("[verifier.deterministic] skipped process_running for non-process checkpoint subject '{}'",
                    name);
            return null;
        }
        ObjectNode args = MAPPER.createObjectNode();
        args.put("name", normalizedName);
        args.put("raw_name", name);
        String resp;
        try {
            resp = invokeVerifierTool("process_running", args);
        } catch (RuntimeException ex) {
            log.warn("[verifier.deterministic] process_running threw for '{}': {}", normalizedName, ex.toString());
            return null;  // Fall through to LLM
        }
        boolean running;
        try {
            JsonNode n = MAPPER.readTree(resp == null ? "{}" : resp);
            if (n.has("error")) return null;  // Tool error → let LLM handle it
            running = n.path("running").asBoolean(false);
        } catch (Exception ex) {
            return null;
        }
        boolean ok = (running == expectRunning);
        String reason = "[deterministic] process_running(" + normalizedName + ") = " + running
                + ", expected " + expectRunning + " → " + (ok ? "ACCEPT" : "REJECT");
        if (emit != null && runId != null) {
            emit.accept(Events.of(runId, "checkpoint_deterministic",
                    "Pre-flight: " + reason,
                    Events.meta()
                            .put("checkpoint", checkpoint)
                            .put("rawName", name)
                            .put("name", normalizedName)
                            .put("expectedRunning", String.valueOf(expectRunning))
                            .put("observedRunning", String.valueOf(running))
                            .put("ok", String.valueOf(ok))
                            .build()));
        }
        return new Verification(ok, reason);
    }

    private String invokeVerifierTool(String tool, JsonNode args) {
        try {
            switch (tool) {
                case "clipboard_text":
                    return tools.clipboardText();
                case "ls": {
                    String path = args.path("dir").asText(args.path("path").asText("/workspace"));
                    return tools.filesList(path);
                }
                case "file_stat": {
                    String path = args.path("path").asText("");
                    if (path.isBlank()) return "{\"error\":\"missing path\"}";
                    return tools.fileStat(path);
                }
                case "read_file": {
                    String path = args.path("path").asText("");
                    if (path.isBlank()) return "{\"error\":\"missing path\"}";
                    return tools.readFileText(path);
                }
                case "find_recent": {
                    String dir = args.path("dir").asText("/workspace");
                    long since = args.path("since_ms").asLong(0);
                    String listJson = tools.filesList(dir);
                    try {
                        JsonNode root = MAPPER.readTree(listJson);
                        JsonNode entries = root.path("entries");
                        ArrayNode filtered = MAPPER.createArrayNode();
                        long sinceSec = since / 1000;
                        if (entries.isArray()) {
                            for (JsonNode entry : entries) {
                                if (entry.path("mtime").asLong(0) >= sinceSec) filtered.add(entry);
                            }
                        }
                        ObjectNode out = MAPPER.createObjectNode();
                        out.put("dir", dir);
                        out.put("since_ms", since);
                        out.set("entries", filtered);
                        return MAPPER.writeValueAsString(out);
                    } catch (Exception ex) {
                        return "{\"error\":\"" + ex.getMessage() + "\"}";
                    }
                }
                case "process_running": {
                    String rawName = args.path("raw_name").asText(args.path("name").asText(""));
                    String name = normalizeVerifierProcessQuery(args.path("name").asText(""));
                    if (name.isBlank()) return "{\"running\":false,\"reason\":\"missing name\"}";
                    String bundleJson = tools.evidenceBundle(null, null);
                    try {
                        JsonNode root = MAPPER.readTree(bundleJson == null ? "{}" : bundleJson);
                        boolean hit = false;
                        ArrayNode matches = MAPPER.createArrayNode();
                        for (JsonNode arr : List.of(root.path("running_windows"),
                                root.path("running_processes_no_window"))) {
                            if (arr.isArray()) {
                                for (JsonNode w : arr) {
                                    if (processEvidenceMatchesQuery(name, w)) {
                                        hit = true;
                                        ObjectNode match = MAPPER.createObjectNode();
                                        match.put("title", w.path("title").asText(""));
                                        match.put("wm_class", w.path("wm_class").asText(""));
                                        match.put("app", w.path("app").asText(""));
                                        match.put("proc", w.path("proc").asText(""));
                                        match.put("comm", w.path("comm").asText(""));
                                        matches.add(match);
                                    }
                                }
                            }
                        }
                        ObjectNode out = MAPPER.createObjectNode();
                        out.put("name", name);
                        out.put("raw_name", rawName);
                        out.put("running", hit);
                        out.set("matches", matches);
                        return MAPPER.writeValueAsString(out);
                    } catch (Exception ex) {
                        return "{\"error\":\"" + ex.getMessage() + "\"}";
                    }
                }
                default:
                    return "{\"error\":\"unknown tool: " + tool + "\"}";
            }
        } catch (RuntimeException ex) {
            return "{\"error\":\"" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + "\"}";
        }
    }

    private static void appendVerifierEvidence(JsonNode node, List<String> evidenceUsed) {
        appendVerifierEvidenceField(node, evidenceUsed, "evidence_cited");
        appendVerifierEvidenceField(node, evidenceUsed, "evidence_used");
    }

    private static void appendVerifierEvidenceField(JsonNode node, List<String> evidenceUsed,
            String field) {
        if (node == null || evidenceUsed == null || field == null) return;
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) return;
        for (JsonNode e : arr) {
            String c = e.asText("").trim();
            if (!c.isEmpty()) evidenceUsed.add(c);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public record Result(boolean success, String summary, int steps, String reason) {
        public static Result success(String summary, int steps) {
            return new Result(true, summary, steps, "completed");
        }
        public static Result failure(String reason, int steps) {
            return new Result(false, reason, steps, reason);
        }
    }

    private static final String DEFAULT_BASE_PROMPT_REF = "<<default>>";

    private static final String FALLBACK_BASE_PROMPT =
            """
            Act like a human on the live screen. Output only complete <tools> blocks.
            Tools: screenshot, launch_app, list_apps, click, type, hotkey, scroll, wait, write_file, read_file, done, fail.
            Never use python, selenium, shell, requests, or invented data. For file tasks call write_file before done.
            In every <reason>, state the previous observation and how this exact action advances the user's requested end goal.
            If the active image/window/file is not the requested one, stop and switch to the requested one first.
            Prefer keyboard shortcuts only when the user task or visible UI makes them clear.
            Do not invent application-specific shortcuts.
            Use hotkey args as {"combo":"ctrl+shift+d"} or {"keys":["ctrl","shift","d"]}.
            Example: <tools><tool name="write_file"><reason>save file</reason><args>{"path":"/workspace/agent-demo/result.md","content":"# Result\\n..."}</args></tool><tool name="done"><reason>finish</reason><args>{"summary":"Saved result.md"}</args></tool></tools>
            """;

    /**
     * Durable per-checkpoint plan that lives outside the LLM call. Created
     * once when the planner emits action="plan_waypoints"; walked one step
     * per iteration WITHOUT any further planner LLM round-trip until the
     * waypoint list is exhausted. Fixes the "random-walk-around-the-donkey"
     * failure where each iteration's screenshot-driven anchor decision had
     * no memory of where the path started or where it was headed.
     *
     * Each waypoint is a JsonNode with at minimum {description} or {x, y}.
     * The runtime grounds description→x,y via UGround when needed and
     * dispatches `toolAction` (default "click", may be "drag" /
     * "modified_click" / etc.) at that point. After the last waypoint,
     * if `closeWith` is set, the runtime fires a hotkey of that combo
     * (e.g. "Return" to close a Bezier path).
     */
    private static final class WaypointPlan {
        final String checkpointId;
        final java.util.List<JsonNode> waypoints;
        final String toolAction;
        final String closeWith;
        int currentIndex = 0;
        int surpriseCount = 0;

        WaypointPlan(String checkpointId, java.util.List<JsonNode> waypoints,
                     String toolAction, String closeWith) {
            this.checkpointId = checkpointId;
            this.waypoints = java.util.List.copyOf(waypoints);
            this.toolAction = (toolAction == null || toolAction.isBlank()) ? "click" : toolAction;
            this.closeWith = (closeWith == null || closeWith.isBlank()) ? null : closeWith;
        }

        boolean isDone() { return currentIndex >= waypoints.size(); }
        JsonNode current() { return waypoints.get(currentIndex); }
        void advance() { currentIndex++; surpriseCount = 0; }
        int remaining() { return Math.max(0, waypoints.size() - currentIndex); }
    }
}
