package com.nubian.ai.app.verifier;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java diff utilities for verifier context.
 * Computes window and app-install deltas between two evidence bundles.
 */
public final class EvidenceDiff {

    private EvidenceDiff() {}

    // -----------------------------------------------------------------------
    // Window diff
    // -----------------------------------------------------------------------

    public record TitleChange(String wmClass, String oldTitle, String newTitle) {}

    public record WindowsDiff(
            List<JsonNode> added,
            List<JsonNode> removed,
            List<TitleChange> titleChanged) {

        public String renderForPrompt() {
            if (added.isEmpty() && removed.isEmpty() && titleChanged.isEmpty()) {
                return "no change";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : added) {
                sb.append("+ ").append(wmClass(n)).append(" — ").append(title(n)).append('\n');
            }
            for (JsonNode n : removed) {
                sb.append("- ").append(wmClass(n)).append(" — ").append(title(n)).append('\n');
            }
            for (TitleChange tc : titleChanged) {
                sb.append("~ ").append(tc.wmClass())
                        .append(" «").append(tc.oldTitle()).append("» → «").append(tc.newTitle()).append("»\n");
            }
            return sb.toString().trim();
        }
    }

    /**
     * Compute window diff between two {@code running_windows} JSON arrays.
     * Match windows by (wm_class, pid) pair; fall back to wm_class only when pid absent.
     *
     * @param beforeWindows JSON array node (may be null or missing)
     * @param afterWindows  JSON array node (may be null or missing)
     */
    public static WindowsDiff windowsDiff(JsonNode beforeWindows, JsonNode afterWindows) {
        Map<String, JsonNode> before = indexWindows(beforeWindows);
        Map<String, JsonNode> after = indexWindows(afterWindows);

        List<JsonNode> added = new ArrayList<>();
        List<JsonNode> removed = new ArrayList<>();
        List<TitleChange> titleChanged = new ArrayList<>();

        for (Map.Entry<String, JsonNode> e : after.entrySet()) {
            JsonNode afterNode = e.getValue();
            JsonNode beforeNode = before.get(e.getKey());
            if (beforeNode == null) {
                added.add(afterNode);
            } else {
                String oldTitle = title(beforeNode);
                String newTitle = title(afterNode);
                if (!oldTitle.equals(newTitle)) {
                    titleChanged.add(new TitleChange(wmClass(afterNode), oldTitle, newTitle));
                }
            }
        }
        for (Map.Entry<String, JsonNode> e : before.entrySet()) {
            if (!after.containsKey(e.getKey())) {
                removed.add(e.getValue());
            }
        }
        return new WindowsDiff(added, removed, titleChanged);
    }

    private static Map<String, JsonNode> indexWindows(JsonNode arr) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (arr == null || !arr.isArray()) return map;
        for (JsonNode n : arr) {
            String key = windowKey(n);
            map.put(key, n);
        }
        return map;
    }

    private static String windowKey(JsonNode n) {
        String cls = wmClass(n);
        String pid = n.path("pid").asText("");
        if (!pid.isBlank() && !"0".equals(pid)) {
            return cls + "#" + pid;
        }
        return cls;
    }

    static String wmClass(JsonNode n) {
        if (n == null) return "";
        String v = n.path("wm_class").asText("");
        if (!v.isBlank()) return v;
        return n.path("class").asText("");
    }

    static String title(JsonNode n) {
        if (n == null) return "";
        String v = n.path("title").asText("");
        if (!v.isBlank()) return v;
        return n.path("name").asText("");
    }

    // -----------------------------------------------------------------------
    // Apps installed diff
    // -----------------------------------------------------------------------

    public record AppsDiff(
            List<JsonNode> installed,
            List<JsonNode> uninstalled) {

        public String renderForPrompt() {
            if (installed.isEmpty() && uninstalled.isEmpty()) {
                return "no change";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : installed) {
                sb.append("+ ").append(appName(n)).append('\n');
            }
            for (JsonNode n : uninstalled) {
                sb.append("- ").append(appName(n)).append('\n');
            }
            return sb.toString().trim();
        }
    }

    /**
     * Compute app-install diff between two apps-catalog JSON arrays.
     * Match by .desktop file name (desktop_file field), falling back to app name.
     *
     * @param beforeApps JSON array node (may be null or missing)
     * @param afterApps  JSON array node (may be null or missing)
     */
    public static AppsDiff appsInstalledDiff(JsonNode beforeApps, JsonNode afterApps) {
        Map<String, JsonNode> before = indexApps(beforeApps);
        Map<String, JsonNode> after = indexApps(afterApps);

        List<JsonNode> installed = new ArrayList<>();
        List<JsonNode> uninstalled = new ArrayList<>();

        for (Map.Entry<String, JsonNode> e : after.entrySet()) {
            if (!before.containsKey(e.getKey())) {
                installed.add(e.getValue());
            }
        }
        for (Map.Entry<String, JsonNode> e : before.entrySet()) {
            if (!after.containsKey(e.getKey())) {
                uninstalled.add(e.getValue());
            }
        }
        return new AppsDiff(installed, uninstalled);
    }

    private static Map<String, JsonNode> indexApps(JsonNode arr) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (arr == null || !arr.isArray()) return map;
        for (JsonNode n : arr) {
            String key = appKey(n);
            map.put(key, n);
        }
        return map;
    }

    private static String appKey(JsonNode n) {
        String desktop = n.path("desktop_file").asText("");
        if (!desktop.isBlank()) return desktop;
        return appName(n);
    }

    static String appName(JsonNode n) {
        if (n == null) return "";
        return n.path("name").asText("");
    }
}
