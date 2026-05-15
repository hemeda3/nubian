package com.nubian.ai.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-checkpoint structured outcome memory.
 *
 * <p>One row per planner-emitted screen action. The row is born {@code PENDING}
 * when the planner picks an action, then mutated by the same nodes that already
 * produce signals downstream:
 * <ul>
 *   <li>The tool node sets {@code TOOL_FAILED} or {@code NO_CHANGE} if its own
 *       result is empty.</li>
 *   <li>The verifier node sets {@code VERIFIER_REJECT} or {@code VERIFIER_ACCEPT}
 *       when the active checkpoint is judged.</li>
 * </ul>
 *
 * <p>Forward pass: {@link #invalidatedSummary(String)} is injected into the
 * planner input as a structured data block. The planner is expected to read
 * the block and not re-emit any listed route. The runtime independently
 * enforces this via {@link #isInvalidated(String, String)}; if the planner
 * regresses, the runtime forces subdivision instead of executing the action.
 *
 * <p>Inheritance: a sub-checkpoint inherits invalidated routes from every
 * ancestor (parents derived by stripping trailing {@code .N} segments from
 * the dot-separated id). What failed at depth K cannot be re-tried at K+1.
 *
 * <p>This class is data only — no LLM calls, no string heuristics about task
 * domain, no keyword tables. The route hash and outcome are computed from the
 * planner's own action fields and the runtime's existing tool/verifier signals.
 */
public final class BlameLedger {

    public enum Status { PENDING, TOOL_FAILED, NO_CHANGE, VERIFIER_REJECT, VERIFIER_ACCEPT }

    public record BlameRow(
            int rowId,
            int turn,
            String checkpointId,
            String description,
            String routeHash,
            Status status,
            String reason) {}

    private final Map<String, List<BlameRow>> rowsByCheckpoint = new LinkedHashMap<>();
    private int nextRowId = 1;

    /** Append a fresh PENDING row for the active checkpoint. Returns row id. */
    public synchronized int recordPending(String checkpointId, int turn,
            String description, String routeHash) {
        int id = nextRowId++;
        BlameRow row = new BlameRow(id, turn,
                norm(checkpointId), nullToBlank(description), nullToBlank(routeHash),
                Status.PENDING, "");
        rowsByCheckpoint.computeIfAbsent(norm(checkpointId), k -> new ArrayList<>()).add(row);
        return id;
    }

    public synchronized void update(int rowId, Status status, String reason) {
        for (List<BlameRow> rows : rowsByCheckpoint.values()) {
            for (int i = 0; i < rows.size(); i++) {
                BlameRow r = rows.get(i);
                if (r.rowId() == rowId) {
                    rows.set(i, new BlameRow(r.rowId(), r.turn(), r.checkpointId(),
                            r.description(), r.routeHash(), status, nullToBlank(reason)));
                    return;
                }
            }
        }
    }

    /** Mark every PENDING row for this checkpoint and its descendants as the
     *  given status. Used when the verifier judges the whole checkpoint. */
    public synchronized void resolvePendingFor(String checkpointId, Status status, String reason) {
        String key = norm(checkpointId);
        for (Map.Entry<String, List<BlameRow>> entry : rowsByCheckpoint.entrySet()) {
            if (!isSelfOrDescendant(entry.getKey(), key)) continue;
            List<BlameRow> rows = entry.getValue();
            for (int i = 0; i < rows.size(); i++) {
                BlameRow r = rows.get(i);
                if (r.status() != Status.PENDING) continue;
                rows.set(i, new BlameRow(r.rowId(), r.turn(), r.checkpointId(),
                        r.description(), r.routeHash(), status, nullToBlank(reason)));
            }
        }
    }

    /** Auto-invalidate any PENDING row in this checkpoint chain whose route hash
     *  matches. Called before recording a new emission: if the planner is about
     *  to emit a route already in flight, the prior emission produced no
     *  resolution-grade signal (no verifier_reject, no tool_failed, no no_change)
     *  yet the planner is back asking the same question. That round-trip IS the
     *  rejection signal — there is no new information for the runtime to wait
     *  on. Mark the prior row NO_CHANGE so {@link #isInvalidated} blocks the
     *  re-emission and the runtime forces subdivide instead. */
    public synchronized void invalidatePriorPending(String checkpointId, String routeHash) {
        if (routeHash == null || routeHash.isBlank()) return;
        int priorPendingCount = 0;
        String id = norm(checkpointId);
        while (!id.isEmpty()) {
            List<BlameRow> rows = rowsByCheckpoint.get(id);
            if (rows != null) {
                for (BlameRow r : rows) {
                    if (r.status() == Status.PENDING && routeHash.equals(r.routeHash())) {
                        priorPendingCount++;
                    }
                }
            }
            int dot = id.lastIndexOf('.');
            if (dot < 0) break;
            id = id.substring(0, dot);
        }
        if (priorPendingCount < 2) return;
        id = norm(checkpointId);
        while (!id.isEmpty()) {
            List<BlameRow> rows = rowsByCheckpoint.get(id);
            if (rows != null) {
                for (int i = 0; i < rows.size(); i++) {
                    BlameRow r = rows.get(i);
                    if (r.status() == Status.PENDING && routeHash.equals(r.routeHash())) {
                        rows.set(i, new BlameRow(r.rowId(), r.turn(), r.checkpointId(),
                                r.description(), r.routeHash(), Status.NO_CHANGE,
                                "third emission without resolution"));
                    }
                }
            }
            int dot = id.lastIndexOf('.');
            if (dot < 0) break;
            id = id.substring(0, dot);
        }
    }

    /** Returns true if any ancestor or this checkpoint has an invalid (non-pending,
     *  non-accept) row whose route hash matches. */
    public synchronized boolean isInvalidated(String checkpointId, String routeHash) {
        if (routeHash == null || routeHash.isBlank()) return false;
        for (BlameRow r : ancestorRows(checkpointId)) {
            if (isInvalidStatus(r.status()) && routeHash.equals(r.routeHash())) {
                return true;
            }
        }
        return false;
    }

    /** Structured text block for planner input. Empty string when nothing to show.
     *  Capped at the most recent {@value #BLAME_RENDER_CAP} invalidated routes —
     *  older entries don't pay for their tokens since the planner only needs to
     *  avoid the recent ones. */
    public synchronized String invalidatedSummary(String checkpointId) {
        List<BlameRow> rows = ancestorRows(checkpointId);
        List<BlameRow> invalid = new ArrayList<>();
        for (BlameRow r : rows) {
            if (isInvalidStatus(r.status())) invalid.add(r);
        }
        if (invalid.isEmpty()) return "";
        int from = Math.max(0, invalid.size() - BLAME_RENDER_CAP);
        int dropped = from;
        StringBuilder sb = new StringBuilder();
        sb.append("[invalidated routes for this checkpoint]\n");
        sb.append("Routes already attempted that did not advance the checkpoint. ");
        sb.append("Pick a different route or emit subdivide_checkpoint.\n");
        if (dropped > 0) {
            sb.append("(showing last ").append(BLAME_RENDER_CAP).append(" of ")
                    .append(invalid.size()).append(" invalidations)\n");
        }
        for (int i = from; i < invalid.size(); i++) {
            BlameRow r = invalid.get(i);
            sb.append("- ");
            if (!r.description().isBlank()) {
                sb.append('"').append(truncate(r.description(), 80)).append("\" ");
            }
            sb.append("[").append(r.routeHash()).append("] ")
                    .append(r.status().name().toLowerCase());
            if (!r.reason().isBlank()) sb.append(": ").append(truncate(r.reason(), 80));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static final int BLAME_RENDER_CAP = 3;

    /** Per-tool inventory across ancestor chain: counts of all attempts and of
     *  invalid attempts. Lets the planner see, structurally, which tool classes
     *  it has already burned at this checkpoint and which it has not yet
     *  exercised. Pure data — no keywords, no task knowledge. */
    public synchronized String toolInventory(String checkpointId) {
        Map<String, int[]> byTool = new LinkedHashMap<>();
        for (BlameRow r : ancestorRows(checkpointId)) {
            String tool = extractTool(r.routeHash());
            int[] counts = byTool.computeIfAbsent(tool, k -> new int[2]);
            counts[0]++;
            if (isInvalidStatus(r.status())) counts[1]++;
        }
        if (byTool.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("[tool inventory for this checkpoint chain]\n");
        for (Map.Entry<String, int[]> entry : byTool.entrySet()) {
            int[] counts = entry.getValue();
            sb.append("- ").append(entry.getKey())
                    .append(": tried=").append(counts[0])
                    .append(", invalid=").append(counts[1])
                    .append('\n');
        }
        return sb.toString();
    }

    private static String extractTool(String routeHash) {
        if (routeHash == null) return "";
        int bar = routeHash.indexOf('|');
        return bar < 0 ? routeHash : routeHash.substring(0, bar);
    }

    /** Number of invalid rows for this checkpoint and its ancestors. */
    public synchronized int invalidCount(String checkpointId) {
        int n = 0;
        for (BlameRow r : ancestorRows(checkpointId)) {
            if (isInvalidStatus(r.status())) n++;
        }
        return n;
    }

    public synchronized List<BlameRow> rowsFor(String checkpointId) {
        return List.copyOf(rowsByCheckpoint.getOrDefault(norm(checkpointId), List.of()));
    }

    public synchronized void clear() {
        rowsByCheckpoint.clear();
        nextRowId = 1;
    }

    /** Drop invalidations for a single checkpoint chain — used when an atomic
     *  escalation is inconclusive and we want to give the planner one more
     *  fresh round on a different route, instead of failing the whole task on
     *  a single-source heuristic. Ancestors and other branches are untouched. */
    public synchronized void clearInvalidations(String checkpointId) {
        if (checkpointId == null) return;
        rowsByCheckpoint.remove(norm(checkpointId));
    }

    /** Stable hash for "same route" detection.
     *
     *  <p>When both x and y are present, the pixel bucket (~30 px) IS the
     *  identity. The description string is the planner's narration of that
     *  pixel and varies turn-to-turn ("Save as... Ctrl+S" vs "Save as... menu
     *  item") even when targeting the same UI element on the same screen.
     *  Treat anything inside one bucket as the same route.
     *
     *  <p>For non-pointer actions (hotkey, type_text without xy,
     *  list_apps, launch_app, write_file, read_file) the hash uses
     *  description + combo + text since there is no pixel to anchor on. */
    public static String routeHash(String toolName, String description,
            Integer x, Integer y, String combo, String text) {
        String t = toolName == null ? "" : toolName.trim().toLowerCase();
        if (x != null && y != null) {
            return t + "|xy|" + (x / 30) + "," + (y / 30);
        }
        return t + "|sym|" + canonical(description) + "|" + canonical(combo) + "|" + canonical(text);
    }

    private List<BlameRow> ancestorRows(String checkpointId) {
        List<BlameRow> out = new ArrayList<>();
        String id = norm(checkpointId);
        while (!id.isEmpty()) {
            List<BlameRow> rows = rowsByCheckpoint.get(id);
            if (rows != null) out.addAll(rows);
            int dot = id.lastIndexOf('.');
            if (dot < 0) break;
            id = id.substring(0, dot);
        }
        Collections.reverse(out);
        return out;
    }

    private static boolean isSelfOrDescendant(String candidate, String ancestor) {
        if (candidate == null || ancestor == null) return false;
        if (candidate.equals(ancestor)) return true;
        return candidate.startsWith(ancestor + ".");
    }

    private static boolean isInvalidStatus(Status s) {
        return s == Status.TOOL_FAILED || s == Status.NO_CHANGE || s == Status.VERIFIER_REJECT;
    }

    private static String canonical(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
    }

    private static String norm(String id) {
        return id == null ? "" : id.trim();
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
