package com.nubian.ai.app.todo;

import com.nubian.ai.app.SeeActPlanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Nested one-active-TODO state for CALM-style desktop runs.
 *
 * <p>The root frame is the normal task checklist. When the active item proves
 * too broad or gets stuck, the planner can push a child frame with smaller
 * observable subtasks. Only the deepest active item is executable. Completed
 * child frames roll their verified evidence up into the parent item.</p>
 */
public final class RecursiveTodoState {
    private static final int MAX_ROOT_ITEMS = 6;
    private static final int MAX_CHILD_ITEMS = 3;
    private static final int MAX_CHILD_DEPTH = 5;
    private static final int MAX_ACCEPTED_PROMPT_ITEMS = 8;
    private static final int MAX_ACCEPTED_PROMPT_CHARS = 1200;

    private final List<Frame> frames = new ArrayList<>();

    public boolean initialized() {
        return !frames.isEmpty();
    }

    public int size() {
        return initialized() ? frames.get(0).items.size() : 0;
    }

    public int doneCount() {
        if (!initialized()) return 0;
        int n = 0;
        for (TodoItem item : frames.get(0).items) {
            if (item.done) n++;
        }
        return n;
    }

    public int depth() {
        return frames.isEmpty() ? 0 : frames.size() - 1;
    }

    public boolean canSubdivide() {
        return initialized() && activeItem() != null && depth() < MAX_CHILD_DEPTH;
    }

    public void initialize(List<SeeActPlanner.ChecklistItem> source) {
        if (initialized() || source == null || source.isEmpty()) return;
        List<TodoItem> items = new ArrayList<>();
        for (SeeActPlanner.ChecklistItem item : source) {
            if (item == null || item.text() == null || item.text().isBlank()) continue;
            String id = item.id() == null || item.id().isBlank()
                    ? String.valueOf(items.size() + 1)
                    : item.id().trim();
            if (containsId(items, id)) continue;
            items.add(new TodoItem(id, item.text().trim(), null, false, ""));
            if (items.size() >= MAX_ROOT_ITEMS) break;
        }
        if (!items.isEmpty()) {
            frames.add(new Frame("root", "", items));
        }
    }

    public boolean pushSubtasks(List<SeeActPlanner.ChecklistItem> source, String reason) {
        TodoItem parent = activeItem();
        if (parent == null || source == null || source.isEmpty() || !canSubdivide()) return false;

        String prefix = parent.id + ".";
        List<TodoItem> children = new ArrayList<>();
        for (SeeActPlanner.ChecklistItem item : source) {
            if (item == null || item.text() == null || item.text().isBlank()) continue;
            String id = prefix + (children.size() + 1);
            // Lineage is a structural reference (parentId), never concatenated into
            // the child's text. The string-concat "X for: Y" form caused the chain
            // to compound across depths and the punctuation-split fallback to chop
            // it into "for: …" sibling fragments. Children store their leaf claim
            // only; renderers walk parentId at use time to produce the lineage
            // context block.
            children.add(new TodoItem(id, item.text().trim(), parent.id, false, ""));
            if (children.size() >= MAX_CHILD_ITEMS) break;
        }
        if (children.isEmpty()) return false;

        String title = parent.id + ": " + parent.text;
        String note = reason == null ? "" : reason.trim();
        frames.add(new Frame(title, note, children));
        return true;
    }

    public boolean pushAutoSubtasks(String reason) {
        return pushAutoSubtasks(reason, "", null);
    }

    public boolean pushAutoSubtasks(String reason, String context) {
        return pushAutoSubtasks(reason, context, null);
    }

    /** Subdivide the active checkpoint into 2-3 sub-acceptance-states.
     *  Runs an LLM splitter that reads the user task + the parent's leaf claim
     *  + the structural lineage block, and returns 2-3 sub-checkpoints. When
     *  the splitter returns empty (or is null), the parent is treated as
     *  atomic — runtime returns false and the caller picks a different route.
     *  There is no punctuation-split fallback: splitting prose on '.' / ',' /
     *  ';' produced for-fragment siblings under the old string-concat lineage
     *  scheme, and the ATOMICITY contract in the splitter prompt explicitly
     *  asks for empty when no honest decomposition exists — the runtime
     *  honours that signal instead of inventing siblings from punctuation. */
    public boolean pushAutoSubtasks(String reason, String context, Splitter splitter) {
        TodoItem parent = activeItem();
        if (parent == null || !canSubdivide()) return false;
        if (splitter == null) return false;

        // Splitter sees the parent's leaf claim only. Lineage is appended to
        // the context arg so the LLM can see the chain without it bleeding
        // back into the child's text.
        String splitterContext = mergeContext(context, lineageBlock(parent));
        List<String> picked = splitter.split(parent.text, reason, splitterContext);
        if (picked == null || picked.size() < 2) return false;

        List<SeeActPlanner.ChecklistItem> items = new ArrayList<>();
        for (String part : picked) {
            String text = part == null ? "" : part.trim();
            if (text.isEmpty()) continue;
            items.add(new SeeActPlanner.ChecklistItem(
                    String.valueOf(items.size() + 1), text, false));
            if (items.size() >= MAX_CHILD_ITEMS) break;
        }
        if (items.size() < 2) return false;
        return pushSubtasks(items, reason);
    }

    /** Splitter contract — implemented by an LLM-backed component in production
     *  and by stubs in tests. Returns 2-3 sub-acceptance-state strings, or
     *  empty list to fall back to generic text-split. */
    @FunctionalInterface
    public interface Splitter {
        List<String> split(String parentCheckpoint, String reason, String context);
    }

    public boolean markActiveDone(String evidence) {
        TodoItem item = activeItem();
        if (item == null || item.done) return false;
        item.done = true;
        item.evidence = evidence == null ? "" : truncate(evidence.trim(), 120);
        rollUpCompletedFrames();
        return true;
    }

    /** Overwrite the active checkpoint's acceptance-state text in place.
     *  Returns true if the update was applied. Used by the supervisor advisor
     *  to surgically rewrite the active plan step's wording — the planner
     *  reads only the plan, so this is how the supervisor steers behaviour
     *  without injecting advisory prose into the planner prompt. */
    public boolean updateActiveText(String newText) {
        if (newText == null || newText.isBlank()) return false;
        TodoItem item = activeItem();
        if (item == null || item.done) return false;
        String trimmed = newText.trim();
        if (trimmed.equals(item.text)) return false;
        item.text = trimmed;
        return true;
    }

    public boolean applyActiveUpdate(SeeActPlanner.ChecklistUpdate update) {
        if (update == null || !update.done()) return false;
        TodoItem item = activeItem();
        if (item == null || update.id() == null) return false;
        if (!item.id.equals(update.id().trim())) return false;
        String evidence = update.evidence() == null ? "" : update.evidence().trim();
        if (evidence.isBlank()) return false;
        return markActiveDone("[planner visible] " + evidence);
    }

    /** Stale-checklist recovery: reset every item back to pending when the
     *  visual world has been mutated since the per-checkpoint verifier last
     *  accepted them (e.g. close_window + Don't-Save mid-task wiped the
     *  produced state). Returns the count of items reset. The reason string
     *  is stamped onto each item's evidence so the trace shows why the
     *  reset happened. */
    public int resetAllToPending(String reason) {
        if (!initialized()) return 0;
        // Collapse any nested frames first — re-work happens at the top level.
        while (frames.size() > 1) frames.remove(frames.size() - 1);
        int n = 0;
        String stamp = "[reset] " + (reason == null ? "stale" : truncate(reason.trim(), 100));
        for (TodoItem item : frames.get(0).items) {
            if (item.done) {
                item.done = false;
                item.evidence = stamp;
                n++;
            }
        }
        return n;
    }

    public boolean allDone() {
        if (!initialized() || frames.size() != 1) return false;
        for (TodoItem item : frames.get(0).items) {
            if (!item.done) return false;
        }
        return true;
    }

    public boolean acceptsPlan(String checkpointId) {
        TodoItem item = activeItem();
        if (item == null) return true;
        // Planner often omits checkpointId on routine actions (click, type,
        // scroll). Treat that as "implicit active": the runtime binds the
        // action to the active checkpoint. Explicit-but-different IDs still
        // get rejected — the planner can only redirect by tagging another
        // checkpoint deliberately, not by silently slipping outside.
        if (checkpointId == null || checkpointId.isBlank()) return true;
        return item.id.equals(checkpointId.trim());
    }

    public String incompleteSummary() {
        if (!initialized()) return "no checklist";
        StringBuilder sb = new StringBuilder();
        for (Frame frame : frames) {
            for (TodoItem item : frame.items) {
                if (item.done) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(item.id).append(": ").append(truncate(item.text, 80));
            }
        }
        return sb.isEmpty() ? "none" : sb.toString();
    }

    public String promptText() {
        TodoItem item = activeItem();
        if (item == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("checkpoint_id=").append(item.id);
        sb.append("\ncheckpoint_text=").append(item.text);
        sb.append("\nDepth ").append(depth()).append("/").append(MAX_CHILD_DEPTH).append(".");
        if (canSubdivide()) {
            sb.append(" If this is complex or stuck, emit subdivide_checkpoint with 2-3 smaller observable subtasks.");
        }
        return sb.toString();
    }

    public String acceptedText() {
        if (!initialized()) return "";
        List<String> accepted = new ArrayList<>();
        for (Frame frame : frames) {
            for (TodoItem item : frame.items) {
                if (!item.done) continue;
                StringBuilder line = new StringBuilder();
                line.append(item.id).append(": ").append(truncate(item.text, 120));
                if (item.evidence != null && !item.evidence.isBlank()) {
                    line.append(" (").append(truncate(item.evidence, 60)).append(")");
                }
                accepted.add(line.toString());
            }
        }
        if (accepted.isEmpty()) return "";

        int start = Math.max(0, accepted.size() - MAX_ACCEPTED_PROMPT_ITEMS);
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append("(older accepted checkpoints summarized; showing last ")
                    .append(accepted.size() - start).append(" of ")
                    .append(accepted.size()).append(")\n");
        }
        for (int i = start; i < accepted.size(); i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(accepted.get(i));
            if (sb.length() >= MAX_ACCEPTED_PROMPT_CHARS) break;
        }
        return truncate(sb.toString(), MAX_ACCEPTED_PROMPT_CHARS);
    }

    public String summary() {
        if (!initialized()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            if (i > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(indent(i)).append("[sub] ").append(frame.title);
                if (!frame.reason.isBlank()) {
                    sb.append(" (").append(truncate(frame.reason, 80)).append(")");
                }
            }
            for (TodoItem item : frame.items) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(indent(i));
                sb.append(item.done ? "[x] " : item == activeItem() ? "[>] " : "[ ] ");
                sb.append(item.id).append(": ").append(item.text);
                if (item.done && item.evidence != null && !item.evidence.isBlank()) {
                    sb.append(" (").append(truncate(item.evidence, 80)).append(")");
                }
            }
        }
        return sb.toString();
    }

    public String activeText() {
        TodoItem item = activeItem();
        return item == null ? "" : item.id + ": " + item.text;
    }

    private void rollUpCompletedFrames() {
        while (frames.size() > 1 && frameDone(frames.get(frames.size() - 1))) {
            Frame child = frames.remove(frames.size() - 1);
            TodoItem parent = activeItem();
            if (parent == null || parent.done) return;
            parent.done = true;
            parent.evidence = "[subtasks] " + truncate(childEvidence(child), 108);
        }
    }

    private TodoItem activeItem() {
        if (!initialized()) return null;
        Frame frame = frames.get(frames.size() - 1);
        for (TodoItem item : frame.items) {
            if (!item.done) return item;
        }
        return null;
    }

    private static boolean frameDone(Frame frame) {
        for (TodoItem item : frame.items) {
            if (!item.done) return false;
        }
        return true;
    }

    private static boolean containsId(List<TodoItem> items, String id) {
        for (TodoItem item : items) {
            if (item.id.equals(id)) return true;
        }
        return false;
    }

    /** Walk the parentId chain bottom-up (immediate parent first, root last)
     *  and return the matching items. Empty list when {@code item} has no
     *  parent or parent ids do not resolve. */
    private List<TodoItem> walkParents(TodoItem item) {
        List<TodoItem> out = new ArrayList<>();
        if (item == null) return out;
        String pid = item.parentId;
        int safety = 0;
        while (pid != null && !pid.isBlank() && safety++ < 32) {
            TodoItem parent = findById(pid);
            if (parent == null) break;
            out.add(parent);
            pid = parent.parentId;
        }
        return out;
    }

    private TodoItem findById(String id) {
        if (id == null || id.isBlank()) return null;
        for (Frame frame : frames) {
            for (TodoItem item : frame.items) {
                if (id.equals(item.id)) return item;
            }
        }
        return null;
    }

    /** Render the lineage of the given item as a multi-line block ready to
     *  paste into a prompt. Returns empty string when there is no lineage. */
    private String lineageBlock(TodoItem item) {
        List<TodoItem> chain = walkParents(item);
        if (chain.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[lineage]");
        for (TodoItem p : chain) {
            sb.append("\n  ↳ ").append(p.text);
        }
        return sb.toString();
    }

    private static String mergeContext(String existing, String addition) {
        String a = existing == null ? "" : existing.trim();
        String b = addition == null ? "" : addition.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + "\n\n" + b;
    }

    /** Public accessor for callers (verifier, splitter wiring) that need the
     *  active checkpoint's lineage as a separate block — never concatenated
     *  into the leaf text. Empty string when the active item is a root. */
    public String activeLineage() {
        return lineageBlock(activeItem());
    }

    private static String childEvidence(Frame frame) {
        StringBuilder sb = new StringBuilder();
        for (TodoItem item : frame.items) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(item.id).append(" ok");
            if (item.evidence != null && !item.evidence.isBlank()) {
                sb.append(": ").append(truncate(item.evidence, 40));
            }
        }
        return sb.toString();
    }

    private static String indent(int depth) {
        if (depth <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static final class Frame {
        private final String title;
        private final String reason;
        private final List<TodoItem> items;

        private Frame(String title, String reason, List<TodoItem> items) {
            this.title = title == null ? "" : title;
            this.reason = reason == null ? "" : reason;
            this.items = items;
        }
    }

    private static final class TodoItem {
        private final String id;
        /** Acceptance-state prose. Mutable so the supervisor advisor can
         *  surgically rewrite the active step's wording via
         *  {@link #updateActiveText(String)}. */
        private String text;
        /** ID of the immediate parent item; null/blank for root-frame items.
         *  Lineage is reconstructed structurally via this reference — the
         *  parent's text is never concatenated into the child's text. */
        private final String parentId;
        private boolean done;
        private String evidence;

        private TodoItem(String id, String text, String parentId, boolean done, String evidence) {
            this.id = id;
            this.text = text;
            this.parentId = parentId;
            this.done = done;
            this.evidence = evidence == null ? "" : evidence;
        }
    }
}
