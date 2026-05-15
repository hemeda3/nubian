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
 * LLM-as-judge picker for SeeAct best-of-N candidates. Builds a single judge
 * call that sees the current screenshot, the user goal + active checkpoint,
 * and the summarised candidate steps. Returns the winning index and a one-line
 * reason. Falls back to index 0 on any parse failure.
 */
@Component
public final class BestOfNJudge {

    private static final Logger log = LoggerFactory.getLogger(BestOfNJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient llm;

    @Value("${nubian.agent.best-of-n.judge-model:}")
    private String judgeModelOverride = "";

    @Value("${nubian.agent.best-of-n.judge-max-tokens:256}")
    private int judgeMaxTokens = 256;

    public BestOfNJudge(LlmClient llm) {
        this.llm = llm;
    }

    public record Verdict(int winnerIndex, String reason, int promptTokens, int completionTokens) {}

    public Verdict pickBest(List<SeeActPlanner.Step> candidates, byte[] screenshot,
            String userTask, String checkpointPrompt) {
        if (candidates == null || candidates.isEmpty()) {
            return new Verdict(0, "no candidates", 0, 0);
        }
        if (candidates.size() == 1) {
            return new Verdict(0, "single candidate", 0, 0);
        }
        StringBuilder body = new StringBuilder();
        body.append("You are picking the BEST next action from ").append(candidates.size())
                .append(" candidate plans for a GUI agent.\n\n");
        body.append("[user goal]\n").append(userTask == null ? "" : userTask.trim()).append('\n');
        if (checkpointPrompt != null && !checkpointPrompt.isBlank()) {
            body.append("\n[active checkpoint]\n").append(checkpointPrompt.trim()).append('\n');
        }
        body.append("\n[candidates]\n");
        for (int i = 0; i < candidates.size(); i++) {
            body.append('[').append(i).append("] ").append(summarize(candidates.get(i))).append('\n');
        }
        body.append("\n[rules]\n");
        body.append("- Prefer the candidate whose action most directly advances the active checkpoint.\n");
        body.append("- Penalize vague element_description (\"the button\") vs specific (\"File menu in the top bar\").\n");
        body.append("- For plan_waypoints, prefer plans whose waypoint descriptions cover the target contour and are ordered sensibly.\n");
        body.append("- A clearly stated reason and a checkpoint_id matching the active one are positive signals.\n");
        body.append("- If two candidates tie, prefer the lower-index one.\n");
        body.append("\nReturn ONLY a JSON object: {\"winner\": <integer>, \"reason\": \"<one line>\"}.\n");

        List<LlmClient.Message> messages = new ArrayList<>(2);
        messages.add(LlmClient.Message.system("You are a strict JSON-only judge. No prose, no markdown."));
        if (screenshot != null && screenshot.length > 0) {
            messages.add(LlmClient.Message.userImage(body.toString(), screenshot));
        } else {
            messages.add(LlmClient.Message.user(body.toString()));
        }

        String model = judgeModelOverride == null || judgeModelOverride.isBlank()
                ? null : LlmClient.costSafeModel(judgeModelOverride);
        try {
            LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, model, messages, 0.0, judgeMaxTokens);
            return parseVerdict(reply, candidates.size());
        } catch (RuntimeException e) {
            log.warn("[best_of_n.judge] failed: {} → defaulting to candidate 0", e.toString());
            return new Verdict(0, "judge_failed: " + e.getMessage(), 0, 0);
        }
    }

    private static Verdict parseVerdict(LlmClient.Reply reply, int n) {
        String raw = reply == null ? null : reply.text();
        if (raw == null || raw.isBlank()) {
            return new Verdict(0, "empty_judge_reply",
                    reply == null ? 0 : reply.promptTokens(),
                    reply == null ? 0 : reply.completionTokens());
        }
        try {
            int brace = raw.indexOf('{');
            int close = raw.lastIndexOf('}');
            String json = (brace >= 0 && close > brace) ? raw.substring(brace, close + 1) : raw;
            JsonNode node = MAPPER.readTree(json);
            int winner = node.path("winner").asInt(0);
            String reason = node.path("reason").asText("");
            if (winner < 0 || winner >= n) winner = 0;
            return new Verdict(winner, reason, reply.promptTokens(), reply.completionTokens());
        } catch (Exception e) {
            return new Verdict(0, "judge_parse_error: " + e.getMessage(),
                    reply.promptTokens(), reply.completionTokens());
        }
    }

    private static String summarize(SeeActPlanner.Step s) {
        if (s == null) return "(null)";
        if (s.isParseError()) return "parse_error: " + s.parseError();
        StringBuilder b = new StringBuilder();
        b.append("action=").append(s.action());
        if (s.elementDescription() != null && !s.elementDescription().isBlank()) {
            b.append(" element=\"").append(truncate(s.elementDescription(), 100)).append('"');
        }
        if (s.hasCoords()) b.append(" xy=").append(s.x()).append(',').append(s.y());
        if (s.combo() != null && !s.combo().isBlank()) b.append(" combo=").append(s.combo());
        if (s.textToType() != null && !s.textToType().isBlank()) {
            b.append(" text=\"").append(truncate(s.textToType(), 60)).append('"');
        }
        if (s.checkpointId() != null && !s.checkpointId().isBlank()) {
            b.append(" cp=").append(s.checkpointId());
        }
        if (s.isBatch()) {
            b.append(" batch=").append(s.batchActions().size()).append(" subactions");
        }
        if ("plan_waypoints".equals(s.action()) && s.batchActions() != null && s.batchActions().isArray()) {
            b.append(" waypoints=").append(s.batchActions().size());
            int max = Math.min(s.batchActions().size(), 4);
            b.append(" first=[");
            for (int i = 0; i < max; i++) {
                JsonNode w = s.batchActions().get(i);
                String d = w.path("description").asText("");
                if (i > 0) b.append("; ");
                b.append(truncate(d, 40));
            }
            if (s.batchActions().size() > max) b.append(", …");
            b.append(']');
        }
        if (s.reason() != null && !s.reason().isBlank()) {
            b.append(" reason=\"").append(truncate(s.reason(), 120)).append('"');
        }
        return b.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
