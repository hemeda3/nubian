package com.nubian.ai.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Text-only consultant for narrow repair questions. It never sees screenshots and
 * never emits actions; the main actor decides what to do with the advice.
 */
@Component("appExpertConsultant")
public final class ExpertConsultant {

    private static final String SYSTEM = """
            You are a text-only technical UI consultant. You receive one narrow desktop issue as text only; no screenshots or images. Answer with 2-3 theoretical options. Do not ask for images. Do not take actions. Keep under 120 words.
            """;

    private final LlmClient llm;

    @Value("${nubian.agent.expert.enabled:true}")
    private boolean enabled = true;

    @Value("${nubian.agent.expert.model:google/gemini-2.5-flash-lite}")
    private String model = "google/gemini-2.5-flash-lite";

    @Value("${nubian.agent.expert.max-tokens:512}")
    private int maxTokens = 512;

    public ExpertConsultant(LlmClient llm) {
        this.llm = llm;
    }

    public boolean enabled() {
        return enabled;
    }

    public String model() {
        return LlmClient.costSafeModel(model);
    }

    public Advice ask(Question q) {
        if (!enabled) return new Advice(false, "expert disabled");
        String question = buildQuestion(q);
        LlmClient.Reply reply = llm.chat(LlmClient.Lane.TEXT, LlmClient.costSafeModel(model), List.of(
                LlmClient.Message.system(SYSTEM),
                LlmClient.Message.user(question)
        ), 0.0, maxTokens);
        String text = reply.text() == null ? "" : reply.text().trim();
        return new Advice(!text.isBlank(), text);
    }

    static String buildQuestion(Question q) {
        StringBuilder sb = new StringBuilder();
        sb.append("[role]\nDesktop UI repair expert for one theoretical question only.\n");
        sb.append("\n[one issue]\nThe agent struggled twice on the same active checkpoint. Give 2-3 options only.\n");
        append(sb, "user goal", q.userGoal());
        append(sb, "active checkpoint", q.activeCheckpoint());
        append(sb, "visible observation described by actor", q.observation());
        append(sb, "planned action", q.plannedAction());
        append(sb, "planned target", q.target());
        append(sb, "actor reason", q.reason());
        append(sb, "goal link", q.goalLink());
        append(sb, "goal trace", q.goalTrace());
        append(sb, "assumption", q.assumption());
        append(sb, "verified by", q.verifiedBy());
        append(sb, "last result", q.lastResult());
        sb.append("\n[answer]\nNo actions, no coordinates unless needed conceptually, no image requests. ");
        sb.append("Use this format: Option 1: ... Option 2: ... Option 3: ...");
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append('\n').append('[').append(label).append("]\n").append(value.trim()).append('\n');
    }

    public record Question(
            String userGoal,
            String activeCheckpoint,
            String observation,
            String plannedAction,
            String target,
            String reason,
            String goalLink,
            String goalTrace,
            String assumption,
            String verifiedBy,
            String lastResult) {}

    public record Advice(boolean ok, String text) {}
}
