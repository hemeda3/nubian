package com.nubian.ai.app.verifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run-scoped in-memory store for screenshots (shots) and action points.
 *
 * <p>Primary index: captureSeq (monotonically increasing integer assigned by putShot).
 * Secondary indexes: by taskSeq (NavigableSet of captureSeqs), by capturedAtMs (NavigableMap).
 * Thread-safe via synchronized methods.
 */
public final class EvidenceStore {

    /** Immutable record for a captured screenshot. */
    public record Shot(
            String runId,
            int taskSeq,
            int captureSeq,
            long capturedAtMs,
            String taskCheckpointId,
            byte[] png) {}

    /** Immutable record for a grounded pointer action (used by PngAnnotator). */
    public record ActionPoint(long ts, int x, int y, String tool) {}

    /**
     * Unified action-timeline event. Pointer events fill x/y; hotkey events fill
     * {@code combo}; type_text events fill {@code text}. Non-pointer events have
     * {@code x == null && y == null} and are not drawn by PngAnnotator.
     */
    public record ActionEvent(long ts, String tool, Integer x, Integer y, String combo, String text) {
        public boolean isPointer() { return x != null && y != null; }

        public String renderForPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append(tool == null ? "?" : tool);
            if (isPointer()) {
                sb.append("@(").append(x).append(",").append(y).append(")");
            }
            if (combo != null && !combo.isBlank()) {
                sb.append(" combo=").append(combo);
            }
            if (text != null && !text.isBlank()) {
                String t = text.length() > 60 ? text.substring(0, 60) + "…" : text;
                sb.append(" text=\"").append(t.replace("\"", "\\\"")).append("\"");
            }
            return sb.toString();
        }
    }

    // Primary storage
    private final Map<Integer, Shot> byCapture = new HashMap<>();
    // taskSeq -> sorted set of captureSeq
    private final Map<Integer, NavigableSet<Integer>> byTask = new HashMap<>();
    // capturedAtMs -> captureSeq (last writer wins on exact ms collision)
    private final NavigableMap<Long, Integer> byTime = new TreeMap<>();
    // taskSeq -> action timeline (pointer + hotkey + type_text) in insertion order
    private final Map<Integer, List<ActionEvent>> actionEvents = new HashMap<>();

    private final AtomicInteger captureSeqCounter = new AtomicInteger(0);

    /** Store a screenshot. Assigns and returns the captureSeq. */
    public synchronized int putShot(int taskSeq, String taskCheckpointId, byte[] png) {
        return putShot(null, taskSeq, taskCheckpointId, System.currentTimeMillis(), png);
    }

    /** Store a screenshot with explicit runId and timestamp (for testing). */
    public synchronized int putShot(String runId, int taskSeq, String taskCheckpointId,
            long capturedAtMs, byte[] png) {
        int seq = captureSeqCounter.incrementAndGet();
        Shot shot = new Shot(runId, taskSeq, seq, capturedAtMs, taskCheckpointId, png);
        byCapture.put(seq, shot);
        byTask.computeIfAbsent(taskSeq, k -> new TreeSet<>()).add(seq);
        byTime.put(capturedAtMs, seq);
        return seq;
    }

    /** Record a pointer action (click / right_click / double_click / hover) for the given taskSeq. */
    public synchronized void putActionPoint(int taskSeq, int x, int y, String tool) {
        actionEvents.computeIfAbsent(taskSeq, k -> new ArrayList<>())
                .add(new ActionEvent(System.currentTimeMillis(), tool, x, y, null, null));
    }

    /** Record a hotkey action for the given taskSeq. {@code combo} like "ctrl+s". */
    public synchronized void putHotkey(int taskSeq, String combo) {
        actionEvents.computeIfAbsent(taskSeq, k -> new ArrayList<>())
                .add(new ActionEvent(System.currentTimeMillis(), "hotkey", null, null, combo, null));
    }

    /** Record a type_text action for the given taskSeq. */
    public synchronized void putTypeText(int taskSeq, String text) {
        actionEvents.computeIfAbsent(taskSeq, k -> new ArrayList<>())
                .add(new ActionEvent(System.currentTimeMillis(), "type_text", null, null, null, text));
    }

    /**
     * Return the last {@code n} shots for the given taskSeq, ordered by captureSeq ascending.
     * If {@code n <= 0}, returns all shots for that task.
     */
    public synchronized List<Shot> getShotsByTask(int taskSeq, int last) {
        NavigableSet<Integer> seqs = byTask.get(taskSeq);
        if (seqs == null || seqs.isEmpty()) return List.of();
        List<Integer> all = new ArrayList<>(seqs);
        List<Integer> slice = (last > 0 && last < all.size())
                ? all.subList(all.size() - last, all.size())
                : all;
        List<Shot> result = new ArrayList<>(slice.size());
        for (int s : slice) {
            Shot shot = byCapture.get(s);
            if (shot != null) result.add(shot);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Return all shots whose capturedAtMs is in [fromMs, toMs], ordered by capturedAtMs ascending.
     */
    public synchronized List<Shot> getShotsInRange(long fromMs, long toMs) {
        NavigableMap<Long, Integer> sub = byTime.subMap(fromMs, true, toMs, true);
        if (sub.isEmpty()) return List.of();
        List<Shot> result = new ArrayList<>(sub.size());
        for (int seq : sub.values()) {
            Shot shot = byCapture.get(seq);
            if (shot != null) result.add(shot);
        }
        result.sort(Comparator.comparingLong(Shot::capturedAtMs));
        return Collections.unmodifiableList(result);
    }

    /**
     * Return only the pointer action points for the given taskSeq, in insertion order.
     * Used by {@code PngAnnotator} to draw rings on the after-screenshot.
     */
    public synchronized List<ActionPoint> getActionPoints(int taskSeq) {
        List<ActionEvent> events = actionEvents.get(taskSeq);
        if (events == null || events.isEmpty()) return List.of();
        List<ActionPoint> pts = new ArrayList<>(events.size());
        for (ActionEvent ev : events) {
            if (ev.isPointer()) pts.add(new ActionPoint(ev.ts(), ev.x(), ev.y(), ev.tool()));
        }
        return Collections.unmodifiableList(pts);
    }

    /**
     * Return the full action timeline for the given taskSeq (clicks + hotkeys + type_text),
     * in insertion order. Used by the verifier to render a structured action timeline.
     */
    public synchronized List<ActionEvent> getActionEvents(int taskSeq) {
        List<ActionEvent> events = actionEvents.get(taskSeq);
        if (events == null || events.isEmpty()) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /** Clear all stored data. Call at run start. */
    public synchronized void clear() {
        byCapture.clear();
        byTask.clear();
        byTime.clear();
        actionEvents.clear();
        captureSeqCounter.set(0);
    }

    /** Total number of shots stored. */
    public synchronized int shotCount() {
        return byCapture.size();
    }
}
