package com.nubian.ai.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.TreeSet;

public final class DoomGuard {

    public static final int DEFAULT_THRESHOLD = 4;
    public static final int HISTORY_SIZE = 10;
    /** If the same fingerprint shows up this many times within {@link #RECENT_WINDOW} calls, block. */
    public static final int RECENT_REPEAT_LIMIT = 2;
    /** Lookback window for non-consecutive repeats (catches A → escape → A patterns). */
    public static final int RECENT_WINDOW = 4;

    private static final ObjectMapper CANON = new ObjectMapper();

    private final int threshold;
    private final Deque<String> history = new ArrayDeque<>();

    public DoomGuard() {
        this(DEFAULT_THRESHOLD);
    }

    public DoomGuard(int threshold) {
        if (threshold < 2) {
            throw new IllegalArgumentException("threshold must be >= 2 (got " + threshold + ")");
        }
        this.threshold = threshold;
    }

    public String check(String toolName, String argsJson) {
        String fp = (toolName == null ? "" : toolName) + ":" + canonicalize(argsJson);
        int streak = 1;
        for (String prior : history) {
            if (prior.equals(fp)) streak++;
            else break;
        }
        history.addFirst(fp);
        while (history.size() > HISTORY_SIZE) history.removeLast();
        if (streak >= threshold) {
            return "DOOM LOOP BLOCKED: " + toolName + " called " + streak
                    + "× with identical args. Change approach.";
        }
        return null;
    }

    static String canonicalize(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            return CANON.writeValueAsString(sortNode(CANON.readTree(json)));
        } catch (Exception ex) {
            return json;
        }
    }

    private static JsonNode sortNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode src = (ObjectNode) node;
            ObjectNode out = CANON.createObjectNode();
            TreeSet<String> keys = new TreeSet<>();
            src.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) out.set(k, sortNode(src.get(k)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode src = (ArrayNode) node;
            ArrayNode out = CANON.createArrayNode();
            for (JsonNode child : src) out.add(sortNode(child));
            return out;
        }
        return node;
    }
}
