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
 * One atomic LLM call that parses the user's free-form goal into a
 * {@link GoalContract}. Runs once at task start so quantifiers, spatial
 * relations, and entity references are extracted before the planner ever
 * decomposes the goal into a checklist.
 *
 * <p>The returned contract is content-only — it never carries app names,
 * task domain, or test-specific facts. It encodes the SHAPE of what the
 * user wants: a list of acceptance items with cardinality and optional
 * spatial relation.
 */
@Component("appGoalContractExtractor")
public final class GoalContractExtractor {

    private static final Logger log = LoggerFactory.getLogger(GoalContractExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM = """
            You parse a user's free-form goal text into a structured contract that
            downstream components will use as the source of truth for planning and
            verification. Read the goal verbatim and produce JSON matching this shape.

            Craft bar: this contract is the foundation every downstream agent
            stands on. A careless extraction cascades into a broken plan and a
            broken result. Be exact, conservative, and faithful to the user's
            words — never invent, never paraphrase a quantifier, never enumerate
            what the user wrote in singular form.



            {"items":[
              {"id":"1",
               "description":"observable end state in plain language",
               "cardinality":"<ANY_ONE|ALL_INSTANCES|EXACT_N>",
               "exact_n":0,
               "spatial":{"relation":"inside|into|in_cell|at|to|on","target_ref":"<short ref>"} or null}
            ]}

            CARDINALITY RULES — extract from the user's quantifier:
              - "any", "a", "an", "one", "some" or NO quantifier on the noun  → ANY_ONE
              - "each", "every", "all"                                        → ALL_INSTANCES
              - explicit number ("3", "five", "ten") on the noun              → EXACT_N with exact_n=<that number>
              The cardinality field is REQUIRED. Default to ANY_ONE when ambiguous.

            SPATIAL RULES — extract when the goal uses containment / position language:
              - "into X", "inside X", "in cell Y", "in the X"     → relation="inside",  target_ref="<X>"
              - "at X", "on X", "to the <element>"                → relation="at",       target_ref="<X>"
              - no spatial language                                → spatial=null
            target_ref is a SHORT structural reference (e.g. "table cell", "URL bar",
            "first column") — never the full noun phrase.

            DECOMPOSITION RULES — split the goal into ITEMS only along independent
            end-states the user explicitly asked for. Do NOT enumerate per-cell or
            per-column when the user used a singular quantifier. Do NOT add
            preconditions (open the app, focus the field) — those are the planner's
            job at execution time, not part of the contract.

            DO NOT include:
              - tool names, x/y coordinates, sandbox-specific terminology
              - app names unless the user wrote them in the goal
              - implementation steps ("click", "type", "press")
              - test-specific examples not present in the goal text

            Output JSON only. No prose. No code fences.
            """;

    private final LlmClient llm;

    @Value("${nubian.agent.goal-contract.model:google/gemini-2.5-flash-lite}")
    private String model = "google/gemini-2.5-flash-lite";

    @Value("${nubian.agent.goal-contract.max-tokens:1024}")
    private int maxTokens = 1024;

    @Value("${nubian.agent.goal-contract.enabled:true}")
    private boolean enabled = true;

    public GoalContractExtractor(LlmClient llm) {
        this.llm = llm;
    }

    public boolean enabled() { return enabled; }

    /** Parses the goal once. Returns an empty contract on any failure so callers
     *  fall back to legacy "derive checklist from raw text" behavior. */
    public GoalContract extract(String userGoal) {
        if (!enabled || userGoal == null || userGoal.isBlank()) {
            return GoalContract.empty(userGoal);
        }
        try {
            String body = "[user goal]\n" + userGoal.trim()
                    + "\n\n[output]\nReturn JSON matching the schema. No prose.\n";
            LlmClient.Reply reply = llm.chat(LlmClient.Lane.TEXT,
                    LlmClient.costSafeModel(model),
                    List.of(LlmClient.Message.system(SYSTEM), LlmClient.Message.user(body)),
                    0.0, maxTokens, LlmClient.CallRole.EXTRACTOR);
            return parse(reply.text(), userGoal);
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            log.warn("[goal_contract] extraction failed: {}", ex.toString());
            return GoalContract.empty(userGoal);
        }
    }

    static GoalContract parse(String raw, String userGoal) {
        if (raw == null || raw.isBlank()) return GoalContract.empty(userGoal);
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return GoalContract.empty(userGoal);
        try {
            JsonNode root = MAPPER.readTree(raw.substring(start, end + 1));
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) return GoalContract.empty(userGoal);
            List<GoalContract.ContractItem> out = new ArrayList<>();
            int idx = 0;
            for (JsonNode n : items) {
                idx++;
                String id = n.path("id").asText("").trim();
                if (id.isEmpty()) id = String.valueOf(idx);
                String description = n.path("description").asText("").trim();
                if (description.isEmpty()) continue;
                GoalContract.Cardinality card = parseCardinality(n.path("cardinality").asText(""));
                int exactN = Math.max(0, n.path("exact_n").asInt(0));
                GoalContract.SpatialRelation spatial = null;
                JsonNode sp = n.path("spatial");
                if (sp.isObject()) {
                    String rel = sp.path("relation").asText("").trim();
                    String ref = sp.path("target_ref").asText("").trim();
                    if (!rel.isEmpty() && !ref.isEmpty()) {
                        spatial = new GoalContract.SpatialRelation(rel, ref);
                    }
                }
                out.add(new GoalContract.ContractItem(id, description, card, exactN, spatial));
            }
            if (out.isEmpty()) return GoalContract.empty(userGoal);
            return new GoalContract(List.copyOf(out), userGoal);
        } catch (Exception ex) {
            return GoalContract.empty(userGoal);
        }
    }

    private static GoalContract.Cardinality parseCardinality(String text) {
        if (text == null) return GoalContract.Cardinality.ANY_ONE;
        String t = text.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (t) {
            case "ALL_INSTANCES", "ALL", "EVERY", "EACH" -> GoalContract.Cardinality.ALL_INSTANCES;
            case "EXACT_N", "EXACT", "N" -> GoalContract.Cardinality.EXACT_N;
            default -> GoalContract.Cardinality.ANY_ONE;
        };
    }
}
