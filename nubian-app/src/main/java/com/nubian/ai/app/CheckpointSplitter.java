package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** One atomic LLM extractor that splits one active checkpoint into 2-3 small,
 *  observable sub-acceptance-states. Replaces the hardcoded verb ladder that
 *  used to live in {@code RecursiveTodoState.specializedVisibleParts} — that
 *  ladder forced "fullscreen/maximized" or "Application is running" templates
 *  on every checkpoint regardless of task intent (e.g. "close GIMP"), which
 *  silently inverted the goal. The LLM, not Java string-matching, decides what
 *  the acceptance criteria of a specific checkpoint should be. */
@Component("appCheckpointSplitter")
public final class CheckpointSplitter {

    private static final Logger log = LoggerFactory.getLogger(CheckpointSplitter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM = """
            You split one active checkpoint into 2 or 3 small, observable sub-acceptance-states.

            Craft bar: produce the cleanest minimal split a careful professional
            would write. Each sub-state must earn its slot — no padding, no
            duplicate axes, no haste-quality output.


            Read the user's original goal and the active checkpoint. Each sub-state is a
            single visible end-state, not an action. Each sub-state must be consistent with
            the user's original goal. If the goal is to close, quit, or remove something,
            the sub-states must describe the absent/closed state — never the running state.
            If the goal is to open, install, or set up something, the sub-states must
            describe the present/ready state.

            NECESSITY RULE — each sub-state, when true, MUST be required for the parent
            checkpoint to be true. If the parent could be true while a candidate sub-state
            is false, that candidate is on the wrong axis and must be dropped. Examples of
            wrong-axis candidates to NEVER produce: changing the *installation* status when
            the parent is about *running* status; changing *visibility* when the parent is
            about *existence*; changing *unsaved-changes* when the parent is about
            *foreground vs background*. Stay on the same axis as the parent assertion.

            ATOMICITY — if the parent is already atomic (one observable end-state on one
            axis), do not invent extra siblings on different axes just to fill 2-3 slots.
            Return an empty subtasks list rather than over-decompose; the runtime treats
            empty as "not splittable, verify the parent directly".

            SPATIAL DECOMPOSITION — when the parent contains spatial / containment
            language ("into X", "inside X", "in cell Y", "at <element>", "to the
            <element>"), the parent is NOT atomic even if it looks like one
            sentence. Always split it into:
              1) a focus-precondition child stating that the cursor / focus is
                 positioned inside the target structure (e.g. "Cursor is inside
                 the first cell of the table"), and
              2) a content-result child stating the desired content state in
                 that structure (e.g. "Text 'X' is present inside the first
                 cell of the table").
            This guards against the failure mode where text gets typed at the
            top of the document while a table sits below — both are visible on
            screen, but the goal's "into" was never honored. The focus child
            forces a narrower verification before the content child runs.

            Do not invent steps the parent checkpoint does not require. Do not add
            workspace-readiness steps unless the parent text explicitly requires them.
            Do not add export, save, or finish steps unless the parent text requires them.
            Output JSON only: {"subtasks":["...","...","..."]} — or {"subtasks":[]} if the
            parent is atomic and cannot be decomposed without changing axis.
            Each string is one sub-acceptance-state, max 90 characters, no leading number.
            """;

    private final LlmClient llm;

    @Value("${nubian.agent.splitter-model:google/gemini-2.5-flash-lite}")
    private String model = "google/gemini-2.5-flash-lite";

    @Value("${nubian.agent.splitter-max-tokens:512}")
    private int maxTokens = 512;

    public CheckpointSplitter(LlmClient llm) {
        this.llm = llm;
    }

    public String model() { return LlmClient.costSafeModel(model); }

    /** Returns 2-3 sub-acceptance-states for the active checkpoint, or an empty
     *  list if extraction failed. Caller must fall back to a generic strategy
     *  (e.g. text-split) on empty. */
    public List<String> split(String userTask, String parentCheckpoint, String reason, String context) {
        if (parentCheckpoint == null || parentCheckpoint.isBlank()) return List.of();

        StringBuilder body = new StringBuilder();
        body.append("[user goal]\n").append(userTask == null ? "" : userTask.trim()).append('\n');
        body.append("\n[active checkpoint]\n").append(parentCheckpoint.trim()).append('\n');
        if (reason != null && !reason.isBlank()) {
            body.append("\n[why we are subdividing]\n").append(reason.trim()).append('\n');
        }
        if (context != null && !context.isBlank()) {
            body.append("\n[last observation]\n").append(context.trim()).append('\n');
        }
        body.append("\n[output]\nReturn JSON only: {\"subtasks\":[\"...\",\"...\"]}\n");

        try {
            LlmClient.Reply reply = llm.chat(LlmClient.Lane.TEXT, model,
                    List.of(LlmClient.Message.system(SYSTEM), LlmClient.Message.user(body.toString())),
                    0.0, maxTokens, LlmClient.CallRole.EXTRACTOR);
            return parseSubtasks(reply.text());
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof InterruptedException) throw ex;
            log.warn("[splitter] failed: {}", ex.toString());
            return List.of();
        }
    }

    static List<String> parseSubtasks(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return List.of();
        try {
            JsonNode root = MAPPER.readTree(raw.substring(start, end + 1));
            JsonNode arr = root.path("subtasks");
            if (!arr.isArray() || arr.isEmpty()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode node : arr) {
                String text = node.asText("").trim();
                if (text.isEmpty()) continue;
                if (text.length() > 120) text = text.substring(0, 120);
                out.add(text);
                if (out.size() >= 3) break;
            }
            return out.size() >= 2 ? List.copyOf(out) : List.of();
        } catch (Exception ex) {
            return List.of();
        }
    }
}
