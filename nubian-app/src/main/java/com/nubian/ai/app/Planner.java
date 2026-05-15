package com.nubian.ai.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component("appPlanner")
public final class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private static final String SYSTEM = """
            You are a senior agent supervisor reviewing a fresh task. You will see the user's task and the live desktop screenshot.
            Produce a CONCRETE, ORDERED execution plan as a single numbered list. Each step must contain:
            - one sentence describing the visible UI action (click WHERE, type WHAT, hotkey WHICH key, etc.)
            - a concrete success signal: what the screen should look like AFTER the step
            Be specific: name the visible widget or menu item; give approximate coordinates only when they are obvious from the screenshot; give the value to type.
            Keep TOTAL output under 1500 chars. No preamble, no markdown headers, no code fences. Just the numbered list.
            """;

    private final LlmClient llm;
    private final String plannerModel;
    private final int plannerMaxTokens;

    public Planner(LlmClient llm,
            @Value("${nubian.agent.planner-model:google/gemini-2.5-flash-lite}") String plannerModel,
            @Value("${nubian.agent.planner-max-tokens:32768}") int plannerMaxTokens) {
        this.llm = llm;
        this.plannerModel = plannerModel;
        this.plannerMaxTokens = plannerMaxTokens;
    }

    public String model() { return plannerModel; }

    public String plan(String userTask, byte[] initialScreenshot) {
        if (userTask == null || userTask.isBlank()) return "";
        List<LlmClient.Message> messages = new ArrayList<>();
        messages.add(LlmClient.Message.system(SYSTEM));
        String userText = "[task]\n" + userTask
                + "\n\nReturn the plan as numbered steps and a brief 'Failure modes:' tail. Max 1500 chars.";
        if (initialScreenshot != null && initialScreenshot.length > 0) {
            messages.add(LlmClient.Message.userImage(userText, initialScreenshot));
        } else {
            messages.add(LlmClient.Message.user(userText));
        }
        try {
            LlmClient.Reply reply = llm.chat(LlmClient.Lane.VISION, plannerModel, messages,
                    0.2, plannerMaxTokens);
            String text = reply.text() == null ? "" : reply.text().trim();
            log.info("[planner] model={} produced {} chars, total_tokens={}",
                    plannerModel, text.length(), reply.totalTokens());
            return text;
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            log.warn("[planner] failed: {}", ex.toString());
            return "";
        }
    }
}
