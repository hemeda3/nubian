package com.nubian.ai.sandbox.computeragent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * DTO records mirroring the JSON shapes returned by the Ubuntu-desktop guest agent.
 *
 * <p>All records tolerate unknown JSON fields — the guest may include extra
 * diagnostic data (host info, port maps, URL hints) that the Java side
 * doesn't need to parse strictly.
 */
public final class ComputerAgentResponses {

    private ComputerAgentResponses() {}

    /**
     * {@code GET /health} response.
     *
     * <p>Live shape includes additional diagnostic fields ({@code display},
     * {@code service}, {@code checks}, {@code ports}, {@code urls}) that
     * {@link JsonIgnoreProperties} discards.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthResponse(
            boolean ok,
            String version,
            long uptime
    ) {}

    /**
     * {@code POST /hands/pyautogui} and {@code POST /hands/xdotool} response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionResult(
            boolean ok,
            String message
    ) {}

    /**
     * {@code POST /shell/exec} response. The live agent emits {@code rc} for
     * the process exit code; older guests may emit {@code exit_code}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecResult(
            String stdout,
            String stderr,
            @JsonProperty("rc") @JsonAlias("exit_code") int exitCode
    ) {}

    /**
     * One entry in {@code GET /memory/files/list}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileEntry(
            String path,
            String type,
            long size,
            long mtime
    ) {}

    /**
     * Raw bytes + content-type from {@code GET /memory/files}.
     */
    public record FileBlob(
            byte[] content,
            String contentType
    ) {}

    /**
     * {@code POST /memory/files} response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WriteResult(
            boolean ok,
            long size
    ) {}

    /**
     * One desktop application discovered via {@code GET /apps} or {@code GET /apps/search}.
     * Mirrors the parsed {@code .desktop} entries the guest exposes; unused
     * fields ({@code icon}, {@code mime_types}, {@code keywords}) are tolerated
     * but ignored on the Java side.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DesktopApp(
            String name,
            String exec,
            String comment,
            List<String> categories,
            @JsonProperty("desktop_file") String desktopFile
    ) {}

    /**
     * One running X11 window from {@code GET /eyes/observe}'s {@code running_windows}.
     * {@code bbox} is {@code [x, y, w, h]}; missing/invalid coordinates surface as null.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WindowInfo(
            String xid,
            String desktop,
            String pid,
            @JsonAlias("wm_class") String wmClass,
            String host,
            String title,
            List<Integer> bbox
    ) {}

    /**
     * One filesystem-recent entry from {@code GET /eyes/observe}'s {@code filesystem_recent}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecentFile(
            String path,
            long mtime,
            long size
    ) {}

    /**
     * One running process from {@code GET /eyes/observe}'s {@code running_processes}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunningProcess(
            int pid,
            String name,
            @JsonAlias("cmd") String cmdline,
            @JsonAlias("cpu") double cpuPercent,
            @JsonAlias("mem") double memPercent
    ) {}

    /**
     * Aggregate "state of the world" from {@code GET /eyes/observe}.
     *
     * <p>Fields are best-effort — older guest builds may omit any of them.
     * {@code focused} captures the focused app/window/widget reported by AT-SPI;
     * {@code lastActionResult} echoes the most recent action's outcome (or null
     * if no action has run yet); {@code screenshotPng} is a base64-encoded PNG
     * if the guest opted into inline frame data.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObserveResult(
            JsonNode focused,
            @JsonProperty("running_windows") List<WindowInfo> runningWindows,
            @JsonProperty("recent_focus") List<JsonNode> recentFocus,
            @JsonProperty("installed_apps") List<DesktopApp> installedApps,
            @JsonProperty("filesystem_recent") List<RecentFile> filesystemRecent,
            @JsonProperty("running_processes") List<RunningProcess> runningProcesses,
            @JsonProperty("last_action_result") JsonNode lastActionResult,
            @JsonProperty("atspi_objects") JsonNode atspiObjects,
            @JsonProperty("screenshot_png_base64") String screenshotPng
    ) {}

    /**
     * {@code POST /apps/launch} and {@code POST /windows/activate} response.
     * The guest typically returns {@code ok}, an action description, and any
     * resolved app metadata ({@code matched}, {@code window}); the JsonNode
     * keeps the door open without forcing a strict schema.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LaunchResult(
            boolean ok,
            String message,
            JsonNode matched,
            JsonNode window
    ) {}

    /**
     * {@code POST /eyes/sql} / {@code GET /eyes/sql?q=...} response.
     * {@code rows} is row-major arrays so the model can render the result as
     * a table without needing the column-keyed shape.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SqlResult(
            boolean ok,
            List<String> columns,
            List<List<JsonNode>> rows,
            @JsonProperty("row_count") int rowCount,
            String error
    ) {}

    /**
     * One skill descriptor returned by {@code GET /skills/search}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillEntry(
            String id,
            String name,
            String description,
            List<String> keywords,
            double score,
            JsonNode parameters
    ) {}

    /**
     * {@code POST /skills/run} response. {@code result} is opaque per-skill
     * payload; the most common shape is {@code {"path":"/workspace/..."}}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillRunResult(
            boolean ok,
            String message,
            @JsonAlias("skill_id") String skillId,
            JsonNode result,
            String error
    ) {}
}
