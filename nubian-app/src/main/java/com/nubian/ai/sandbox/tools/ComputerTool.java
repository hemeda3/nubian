package com.nubian.ai.sandbox.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.tool.OpenApiSchema;
import com.nubian.ai.runtime.tool.Tool;
import com.nubian.ai.runtime.tool.ToolResult;
import com.nubian.ai.sandbox.api.SandboxArtifacts;
import com.nubian.ai.sandbox.api.SandboxBrowser;
import com.nubian.ai.sandbox.api.SandboxComputer;
import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.api.SandboxPorts;
import com.nubian.ai.sandbox.api.SandboxTerminal;
import com.nubian.ai.sandbox.model.SandboxArtifact;
import com.nubian.ai.sandbox.model.SandboxBrowserAction;
import com.nubian.ai.sandbox.model.SandboxBrowserObservation;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxCommandResult;
import com.nubian.ai.sandbox.model.SandboxDisplayFrame;
import com.nubian.ai.sandbox.model.SandboxFile;
import com.nubian.ai.sandbox.model.SandboxPort;
import com.nubian.ai.sandbox.registry.SandboxRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * High-level computer tool for agents.
 *
 * <p>This tool is intentionally provider-neutral. It composes the lower-level
 * sandbox SPI capabilities into familiar operations such as run Python, take a
 * desktop screenshot, click the screen, open a URL, and work with the
 * workspace. The raw {@code sandboxCapability} tool remains available as the
 * escape hatch.</p>
 */
public class ComputerTool extends Tool {
    static final List<String> FUNCTION_NAMES = List.of(
            "computer_inspect",
            "computer_run_shell",
            "computer_run_python",
            "computer_run_node",
            "computer_run_go",
            "computer_git",
            "computer_download_url",
            "computer_screenshot",
            "computer_desktop_screenshot",
            "computer_scrot",
            "computer_x_display",
            "computer_vnc_info",
            "computer_xdotool",
            "computer_mouse_click",
            "computer_keyboard_type",
            "computer_inkscape",
            "computer_open_url",
            "computer_workspace",
            "computer_artifact",
            "computer_list_ports");

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final String DEFAULT_WORKDIR = "/workspace";

    private final SandboxRegistry registry;
    private final Duration invocationTimeout;

    public ComputerTool(ObjectMapper objectMapper, SandboxRegistry registry, Duration invocationTimeout) {
        super(objectMapper);
        this.registry = registry;
        this.invocationTimeout = invocationTimeout == null ? DEFAULT_TIMEOUT : invocationTimeout;
    }

    @OpenApiSchema("""
    {
      "name": "computer_inspect",
      "description": "Inspect the selected sandbox computer and report available features, tools, directories, endpoints, and limits.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string", "description": "Sandbox provider id. Omit to use the selected provider."},
          "sessionId": {"type": "string", "description": "Sandbox session id."}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_inspect(Map<String, Object> params) {
        return run("computer inspect", () -> await(computer(capabilities(params)).inspect(sessionId(params))));
    }

    @OpenApiSchema("""
    {
      "name": "computer_run_shell",
      "description": "Run a shell command inside the sandbox computer. Use this as the escape hatch for tools that do not have a dedicated wrapper.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "command": {"type": "string", "description": "Shell command or executable to run."},
          "arguments": {"type": "array", "items": {"type": "string"}},
          "workingDirectory": {"type": "string", "default": "/workspace"},
          "environment": {"type": "object", "additionalProperties": {"type": "string"}},
          "timeoutSeconds": {"type": "integer", "default": 120}
        },
        "required": ["sessionId", "command"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_run_shell(Map<String, Object> params) {
        return run("computer shell", () -> commandResult(runCommand(
                capabilities(params),
                sessionId(params),
                required(params, "command"),
                stringList(params.get("arguments")),
                text(params, "workingDirectory", DEFAULT_WORKDIR),
                stringMap(params.get("environment")),
                timeout(params, DEFAULT_TIMEOUT),
                metadata("computer.wrapper", "run_shell"))));
    }

    @OpenApiSchema("""
    {
      "name": "computer_run_python",
      "description": "Run Python in the sandbox computer using either inline code or a script path.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "code": {"type": "string", "description": "Inline Python code for python3 -c."},
          "path": {"type": "string", "description": "Python script path inside the sandbox."},
          "arguments": {"type": "array", "items": {"type": "string"}},
          "workingDirectory": {"type": "string", "default": "/workspace"},
          "timeoutSeconds": {"type": "integer", "default": 120}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_run_python(Map<String, Object> params) {
        return run("computer python", () -> {
            List<String> args = new ArrayList<>();
            String code = text(params, "code", "");
            String path = text(params, "path", "");
            if (hasText(code)) {
                args.add("-c");
                args.add(code);
            } else if (hasText(path)) {
                args.add(path);
            } else {
                throw new IllegalArgumentException("code or path is required");
            }
            args.addAll(stringList(params.get("arguments")));
            return commandResult(runCommand(
                    capabilities(params),
                    sessionId(params),
                    text(params, "python", "python3"),
                    args,
                    text(params, "workingDirectory", DEFAULT_WORKDIR),
                    Map.of(),
                    timeout(params, DEFAULT_TIMEOUT),
                    metadata("computer.wrapper", "run_python")));
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_run_node",
      "description": "Run Node.js in the sandbox computer using either inline JavaScript or a script path.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "code": {"type": "string", "description": "Inline JavaScript for node -e."},
          "path": {"type": "string", "description": "Node script path inside the sandbox."},
          "arguments": {"type": "array", "items": {"type": "string"}},
          "workingDirectory": {"type": "string", "default": "/workspace"},
          "timeoutSeconds": {"type": "integer", "default": 120}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_run_node(Map<String, Object> params) {
        return run("computer node", () -> {
            List<String> args = new ArrayList<>();
            String code = text(params, "code", "");
            String path = text(params, "path", "");
            if (hasText(code)) {
                args.add("-e");
                args.add(code);
            } else if (hasText(path)) {
                args.add(path);
            } else {
                throw new IllegalArgumentException("code or path is required");
            }
            args.addAll(stringList(params.get("arguments")));
            return commandResult(runCommand(
                    capabilities(params),
                    sessionId(params),
                    "node",
                    args,
                    text(params, "workingDirectory", DEFAULT_WORKDIR),
                    Map.of(),
                    timeout(params, DEFAULT_TIMEOUT),
                    metadata("computer.wrapper", "run_node")));
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_run_go",
      "description": "Run Go code in the sandbox computer. Inline code is written to /tmp/agent then executed with go run.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "code": {"type": "string", "description": "Inline Go source code."},
          "path": {"type": "string", "description": "Go file/package path inside the sandbox."},
          "arguments": {"type": "array", "items": {"type": "string"}},
          "workingDirectory": {"type": "string", "default": "/workspace"},
          "timeoutSeconds": {"type": "integer", "default": 120}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_run_go(Map<String, Object> params) {
        return run("computer go", () -> {
            SandboxRegistry.ProviderCapabilities capabilities = capabilities(params);
            String sessionId = sessionId(params);
            String path = text(params, "path", "");
            String code = text(params, "code", "");
            if (!hasText(path)) {
                if (!hasText(code)) {
                    throw new IllegalArgumentException("code or path is required");
                }
                path = "/tmp/agent/nubian-go-" + UUID.randomUUID() + ".go";
                writeFile(capabilities, sessionId, path, code.getBytes(StandardCharsets.UTF_8), "text/x-go");
            }

            List<String> args = new ArrayList<>();
            args.add("run");
            args.add(path);
            args.addAll(stringList(params.get("arguments")));
            return commandResult(runCommand(
                    capabilities,
                    sessionId,
                    "go",
                    args,
                    text(params, "workingDirectory", DEFAULT_WORKDIR),
                    Map.of(),
                    timeout(params, DEFAULT_TIMEOUT),
                    metadata("computer.wrapper", "run_go")));
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_git",
      "description": "Run a git command inside the sandbox computer, usually in /workspace.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "arguments": {"type": "array", "items": {"type": "string"}, "description": "Git arguments, for example [\\"clone\\", \\"https://github.com/org/repo.git\\"]"},
          "workingDirectory": {"type": "string", "default": "/workspace"},
          "timeoutSeconds": {"type": "integer", "default": 120}
        },
        "required": ["sessionId", "arguments"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_git(Map<String, Object> params) {
        return run("computer git", () -> commandResult(runCommand(
                capabilities(params),
                sessionId(params),
                "git",
                requiredStringList(params, "arguments"),
                text(params, "workingDirectory", DEFAULT_WORKDIR),
                Map.of(),
                timeout(params, DEFAULT_TIMEOUT),
                metadata("computer.wrapper", "git"))));
    }

    @OpenApiSchema("""
    {
      "name": "computer_download_url",
      "description": "Download a URL into the sandbox computer using curl.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "url": {"type": "string"},
          "outputPath": {"type": "string", "description": "Destination path inside the sandbox, such as /downloads/file.bin."},
          "timeoutSeconds": {"type": "integer", "default": 120}
        },
        "required": ["sessionId", "url", "outputPath"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_download_url(Map<String, Object> params) {
        return run("computer download", () -> {
            String outputPath = required(params, "outputPath");
            SandboxCommandResult result = runCommand(
                    capabilities(params),
                    sessionId(params),
                    "curl",
                    List.of("-L", "--fail", "--show-error", "--silent", "--output", outputPath, required(params, "url")),
                    DEFAULT_WORKDIR,
                    Map.of(),
                    timeout(params, DEFAULT_TIMEOUT),
                    metadata("computer.wrapper", "download_url"));
            Map<String, Object> output = commandResult(result);
            output.put("path", outputPath);
            return output;
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_screenshot",
      "description": "Friendly alias for taking a desktop screenshot. Writes a PNG under /logs by default.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "path": {"type": "string", "description": "Optional sandbox output path. Defaults to /logs/screenshot-<timestamp>.png."},
          "includeData": {"type": "boolean", "default": false},
          "registerArtifact": {"type": "boolean", "default": false}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_screenshot(Map<String, Object> params) {
        return computer_desktop_screenshot(params);
    }

    @OpenApiSchema("""
    {
      "name": "computer_desktop_screenshot",
      "description": "Take a desktop screenshot. By default writes a PNG under /logs and returns metadata; set includeData to true to include base64 PNG data.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "path": {"type": "string", "description": "Optional sandbox output path. Defaults to /logs/screenshot-<timestamp>.png."},
          "includeData": {"type": "boolean", "default": false},
          "registerArtifact": {"type": "boolean", "default": false}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_desktop_screenshot(Map<String, Object> params) {
        return run("computer screenshot", () -> {
            SandboxRegistry.ProviderCapabilities capabilities = capabilities(params);
            String sessionId = sessionId(params);
            String path = text(params, "path", "/logs/screenshot-" + Instant.now().toEpochMilli() + ".png");

            Optional<SandboxTerminal> terminal = capabilities.terminal(SandboxTerminal.class);
            if (terminal.isPresent()) {
                return runScrot(capabilities, terminal.get(), sessionId, path, List.of(), params, "desktop_screenshot");
            }

            SandboxDisplayFrame frame = await(display(capabilities).captureFrame(sessionId));
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("path", path);
            output.putAll(displayFrameMap(frame, bool(params, "includeData", false)));
            if (hasCapability(capabilities.fileSystem())) {
                writeFile(capabilities, sessionId, path, frame.data(), frame.mediaType());
            }
            return output;
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_scrot",
      "description": "Run scrot inside the X display to take a screenshot, with friendly options for delay, pointer, and region.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "path": {"type": "string", "description": "Sandbox PNG output path. Defaults to /logs/scrot-<timestamp>.png."},
          "delaySeconds": {"type": "integer", "default": 0},
          "includePointer": {"type": "boolean", "default": false},
          "region": {"type": "object", "description": "Optional region {x,y,width,height} for scrot -a."},
          "arguments": {"type": "array", "items": {"type": "string"}, "description": "Extra raw scrot arguments."},
          "includeData": {"type": "boolean", "default": false},
          "registerArtifact": {"type": "boolean", "default": false}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_scrot(Map<String, Object> params) {
        return run("computer scrot", () -> {
            SandboxRegistry.ProviderCapabilities capabilities = capabilities(params);
            String sessionId = sessionId(params);
            String path = text(params, "path", "/logs/scrot-" + Instant.now().toEpochMilli() + ".png");
            List<String> args = new ArrayList<>();
            int delaySeconds = integer(params, "delaySeconds", 0);
            if (delaySeconds > 0) {
                args.add("-d");
                args.add(Integer.toString(delaySeconds));
            }
            if (bool(params, "includePointer", false)) {
                args.add("-p");
            }
            regionArg(params).ifPresent(region -> {
                args.add("-a");
                args.add(region);
            });
            args.addAll(stringList(params.get("arguments")));
            return runScrot(capabilities, terminal(capabilities), sessionId, path, args, params, "scrot");
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_x_display",
      "description": "Inspect the X display inside the sandbox, including DISPLAY, xdotool geometry, xdpyinfo dimensions, and VNC/noVNC endpoints.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_x_display(Map<String, Object> params) {
        return run("computer X display", () -> {
            SandboxRegistry.ProviderCapabilities capabilities = capabilities(params);
            String sessionId = sessionId(params);
            String command = """
                    printf 'DISPLAY\\t%s\\n' "${DISPLAY:-}"
                    if command -v xdotool >/dev/null 2>&1; then printf 'GEOMETRY\\t'; xdotool getdisplaygeometry; fi
                    if command -v xdpyinfo >/dev/null 2>&1; then xdpyinfo | awk '/dimensions:/{print "DIMENSIONS\\t"$2; exit}'; fi
                    """;
            SandboxCommandResult result = runCommand(
                    capabilities,
                    sessionId,
                    command,
                    List.of(),
                    DEFAULT_WORKDIR,
                    Map.of(),
                    Duration.ofSeconds(20),
                    metadata("computer.wrapper", "x_display"));
            Map<String, Object> output = parseXDisplay(result.stdout());
            output.put("command", commandResult(result));
            output.put("ports", await(ports(capabilities).listPorts(sessionId)).stream()
                    .map(ComputerTool::portMap)
                    .toList());
            return output;
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_vnc_info",
      "description": "Return Docker computer VNC and noVNC viewer endpoint information for the sandbox session.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_vnc_info(Map<String, Object> params) {
        return run("computer VNC info", () -> {
            List<Map<String, Object>> portMaps = await(ports(capabilities(params)).listPorts(sessionId(params))).stream()
                    .map(ComputerTool::portMap)
                    .toList();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ports", portMaps);
            output.put("vnc", firstPort(portMaps, 5900));
            output.put("novnc", firstPort(portMaps, 7900));
            return output;
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_xdotool",
      "description": "Run xdotool directly against the sandbox X display. Prefer mouse/keyboard wrappers for common actions.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "arguments": {"type": "array", "items": {"type": "string"}, "description": "xdotool arguments, for example [\\"getdisplaygeometry\\"] or [\\"mousemove\\",\\"100\\",\\"100\\",\\"click\\",\\"1\\"]"},
          "timeoutSeconds": {"type": "integer", "default": 20}
        },
        "required": ["sessionId", "arguments"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_xdotool(Map<String, Object> params) {
        return run("computer xdotool", () -> commandResult(runCommand(
                capabilities(params),
                sessionId(params),
                "xdotool",
                requiredStringList(params, "arguments"),
                DEFAULT_WORKDIR,
                Map.of(),
                timeout(params, Duration.ofSeconds(20)),
                metadata("computer.wrapper", "xdotool"))));
    }

    @OpenApiSchema("""
    {
      "name": "computer_mouse_click",
      "description": "Move the desktop mouse to screen coordinates and click using xdotool.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "x": {"type": "integer"},
          "y": {"type": "integer"},
          "button": {"type": "integer", "default": 1},
          "timeoutSeconds": {"type": "integer", "default": 20}
        },
        "required": ["sessionId", "x", "y"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_mouse_click(Map<String, Object> params) {
        return run("computer mouse click", () -> commandResult(runCommand(
                capabilities(params),
                sessionId(params),
                "xdotool",
                List.of("mousemove", Integer.toString(integer(params, "x", 0)), Integer.toString(integer(params, "y", 0)),
                        "click", Integer.toString(integer(params, "button", 1))),
                DEFAULT_WORKDIR,
                Map.of(),
                timeout(params, Duration.ofSeconds(20)),
                metadata("computer.wrapper", "mouse_click"))));
    }

    @OpenApiSchema("""
    {
      "name": "computer_keyboard_type",
      "description": "Type text or press a key on the desktop using xdotool.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "text": {"type": "string", "description": "Text to type."},
          "key": {"type": "string", "description": "Single xdotool key name, such as Return, Escape, ctrl+l."},
          "delayMillis": {"type": "integer", "default": 10},
          "timeoutSeconds": {"type": "integer", "default": 20}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_keyboard_type(Map<String, Object> params) {
        return run("computer keyboard", () -> {
            List<String> args = new ArrayList<>();
            String key = text(params, "key", "");
            if (hasText(key)) {
                args.add("key");
                args.add(key);
            } else {
                String text = required(params, "text");
                args.add("type");
                args.add("--delay");
                args.add(Integer.toString(integer(params, "delayMillis", 10)));
                args.add(text);
            }
            return commandResult(runCommand(
                    capabilities(params),
                    sessionId(params),
                    "xdotool",
                    args,
                    DEFAULT_WORKDIR,
                    Map.of(),
                    timeout(params, Duration.ofSeconds(20)),
                    metadata("computer.wrapper", "keyboard_type")));
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_inkscape",
      "description": "Run Inkscape for SVG/vector rendering or export. Use inputPath/outputPath for common export, or raw arguments for advanced CLI use.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "inputPath": {"type": "string", "description": "Input SVG/vector file path inside the sandbox."},
          "outputPath": {"type": "string", "description": "Output file path inside the sandbox."},
          "exportType": {"type": "string", "description": "Optional export type such as png, pdf, svg."},
          "arguments": {"type": "array", "items": {"type": "string"}, "description": "Raw or extra Inkscape arguments."},
          "workingDirectory": {"type": "string", "default": "/workspace"},
          "timeoutSeconds": {"type": "integer", "default": 120},
          "registerArtifact": {"type": "boolean", "default": false}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_inkscape(Map<String, Object> params) {
        return run("computer inkscape", () -> {
            SandboxRegistry.ProviderCapabilities capabilities = capabilities(params);
            String sessionId = sessionId(params);
            List<String> args = new ArrayList<>();
            String inputPath = text(params, "inputPath", "");
            String outputPath = text(params, "outputPath", "");
            if (hasText(inputPath) || hasText(outputPath)) {
                if (!hasText(inputPath) || !hasText(outputPath)) {
                    throw new IllegalArgumentException("inputPath and outputPath are both required for Inkscape export mode");
                }
                args.add(inputPath);
                String exportType = text(params, "exportType", "");
                if (hasText(exportType)) {
                    args.add("--export-type=" + exportType);
                }
                args.add("--export-filename=" + outputPath);
                args.addAll(stringList(params.get("arguments")));
            } else {
                args.addAll(requiredStringList(params, "arguments"));
            }

            SandboxCommandResult result = runCommand(
                    capabilities,
                    sessionId,
                    "inkscape",
                    args,
                    text(params, "workingDirectory", DEFAULT_WORKDIR),
                    Map.of(),
                    timeout(params, DEFAULT_TIMEOUT),
                    metadata("computer.wrapper", "inkscape"));
            Map<String, Object> output = commandResult(result);
            if (hasText(outputPath)) {
                output.put("outputPath", outputPath);
                long sizeBytes = result.successful() ? statPath(capabilities, sessionId, outputPath) : 0L;
                output.put("sizeBytes", sizeBytes);
                if (result.successful() && sizeBytes > 0 && bool(params, "registerArtifact", false)) {
                    try {
                        output.put("artifact", artifactMap(createArtifact(
                                capabilities,
                                sessionId,
                                text(params, "artifactId", defaultArtifactId(Map.of("path", outputPath))),
                                text(params, "name", outputPath.substring(outputPath.lastIndexOf('/') + 1)),
                                outputPath,
                                mediaTypeForPath(outputPath),
                                sizeBytes,
                                null,
                                metadata("computer.wrapper", "inkscape"))));
                    } catch (RuntimeException ex) {
                        output.put("artifactError", rootMessage(ex));
                    }
                }
            }
            return output;
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_open_url",
      "description": "Open a URL in the sandbox browser and return page title, text preview, URLs, and screenshot metadata.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "url": {"type": "string"},
          "includeScreenshot": {"type": "boolean", "default": false},
          "timeoutSeconds": {"type": "integer", "default": 30}
        },
        "required": ["sessionId", "url"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_open_url(Map<String, Object> params) {
        return run("computer open url", () -> {
            SandboxBrowserObservation observation = await(browser(capabilities(params)).performAction(
                    sessionId(params),
                    new SandboxBrowserAction(
                            SandboxBrowserAction.Type.NAVIGATE,
                            Map.of("url", required(params, "url")),
                            timeout(params, Duration.ofSeconds(30)),
                            metadata("computer.wrapper", "open_url"))));
            return browserObservationMap(observation, bool(params, "includeScreenshot", false));
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_workspace",
      "description": "Read, write, list, mkdir, or delete files in the sandbox workspace/filesystem.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "action": {"type": "string", "enum": ["read", "write", "list", "mkdir", "delete"]},
          "path": {"type": "string"},
          "content": {"type": "string"},
          "contentBase64": {"type": "string"},
          "mediaType": {"type": "string", "default": "text/plain"},
          "includeContent": {"type": "boolean", "default": true}
        },
        "required": ["sessionId", "action", "path"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_workspace(Map<String, Object> params) {
        return run("computer workspace", () -> {
            SandboxRegistry.ProviderCapabilities capabilities = capabilities(params);
            String sessionId = sessionId(params);
            String path = required(params, "path");
            SandboxFileSystem fileSystem = fileSystem(capabilities);
            return switch (text(params, "action", "read")) {
                case "write" -> fileMap(writeFile(
                        capabilities,
                        sessionId,
                        path,
                        contentBytes(params),
                        text(params, "mediaType", "text/plain")), false);
                case "list" -> await(fileSystem.listFiles(sessionId, path)).stream()
                        .map(file -> fileMap(file, false))
                        .toList();
                case "mkdir" -> {
                    await(fileSystem.createDirectory(sessionId, path));
                    yield Map.of("status", "created", "path", path);
                }
                case "delete" -> {
                    await(fileSystem.deletePath(sessionId, path));
                    yield Map.of("status", "deleted", "path", path);
                }
                case "read" -> fileMap(await(fileSystem.readFile(sessionId, path)), bool(params, "includeContent", true));
                default -> throw new IllegalArgumentException("Unsupported workspace action: " + params.get("action"));
            };
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_artifact",
      "description": "Create, get, list, or delete sandbox artifacts.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"},
          "action": {"type": "string", "enum": ["create", "get", "list", "delete"]},
          "artifactId": {"type": "string"},
          "name": {"type": "string"},
          "path": {"type": "string"},
          "mediaType": {"type": "string"},
          "sizeBytes": {"type": "integer"},
          "uri": {"type": "string"}
        },
        "required": ["sessionId", "action"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_artifact(Map<String, Object> params) {
        return run("computer artifact", () -> {
            SandboxRegistry.ProviderCapabilities capabilities = capabilities(params);
            String sessionId = sessionId(params);
            SandboxArtifacts artifacts = artifacts(capabilities);
            return switch (text(params, "action", "list")) {
                case "create" -> artifactMap(createArtifact(
                        capabilities,
                        sessionId,
                        text(params, "artifactId", defaultArtifactId(params)),
                        text(params, "name", defaultArtifactName(params)),
                        required(params, "path"),
                        text(params, "mediaType", "application/octet-stream"),
                        longValue(params, "sizeBytes", 0L),
                        uri(params.get("uri")),
                        metadata("computer.wrapper", "artifact")));
                case "get" -> artifactMap(await(artifacts.getArtifact(sessionId, required(params, "artifactId")))
                        .orElseThrow(() -> new IllegalArgumentException("Artifact not found")));
                case "delete" -> {
                    String artifactId = required(params, "artifactId");
                    await(artifacts.deleteArtifact(sessionId, artifactId));
                    yield Map.of("status", "deleted", "artifactId", artifactId);
                }
                case "list" -> await(artifacts.listArtifacts(sessionId)).stream()
                        .map(ComputerTool::artifactMap)
                        .toList();
                default -> throw new IllegalArgumentException("Unsupported artifact action: " + params.get("action"));
            };
        });
    }

    @OpenApiSchema("""
    {
      "name": "computer_list_ports",
      "description": "List exposed ports for a sandbox computer, including WebDriver, VNC, and noVNC when available.",
      "parameters": {
        "type": "object",
        "properties": {
          "providerId": {"type": "string"},
          "sessionId": {"type": "string"}
        },
        "required": ["sessionId"]
      }
    }
    """)
    public CompletableFuture<ToolResult> computer_list_ports(Map<String, Object> params) {
        return run("computer ports", () -> await(ports(capabilities(params)).listPorts(sessionId(params))).stream()
                .map(ComputerTool::portMap)
                .toList());
    }

    private CompletableFuture<ToolResult> run(String operation, Supplier<Object> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return successResponse(supplier.get());
            } catch (Exception ex) {
                return failResponse(operation + " failed: " + rootMessage(ex));
            }
        });
    }

    private SandboxRegistry.ProviderCapabilities capabilities(Map<String, Object> params) {
        String providerId = text(params, "providerId", "");
        if (hasText(providerId)) {
            return registry.resolve(providerId)
                    .orElseThrow(() -> new IllegalArgumentException("Sandbox provider not found: " + providerId));
        }
        return registry.selectedCapabilities()
                .orElseThrow(() -> new IllegalArgumentException("No selected sandbox provider configured"));
    }

    private SandboxComputer computer(SandboxRegistry.ProviderCapabilities capabilities) {
        return capabilities.computer(SandboxComputer.class)
                .orElseThrow(() -> new IllegalArgumentException("Computer capability is not available"));
    }

    private SandboxTerminal terminal(SandboxRegistry.ProviderCapabilities capabilities) {
        return capabilities.terminal(SandboxTerminal.class)
                .orElseThrow(() -> new IllegalArgumentException("Terminal capability is not available"));
    }

    private SandboxFileSystem fileSystem(SandboxRegistry.ProviderCapabilities capabilities) {
        return capabilities.fileSystem(SandboxFileSystem.class)
                .orElseThrow(() -> new IllegalArgumentException("File system capability is not available"));
    }

    private SandboxBrowser browser(SandboxRegistry.ProviderCapabilities capabilities) {
        return capabilities.browser(SandboxBrowser.class)
                .orElseThrow(() -> new IllegalArgumentException("Browser capability is not available"));
    }

    private SandboxDisplay display(SandboxRegistry.ProviderCapabilities capabilities) {
        return capabilities.display(SandboxDisplay.class)
                .orElseThrow(() -> new IllegalArgumentException("Display capability is not available"));
    }

    private SandboxPorts ports(SandboxRegistry.ProviderCapabilities capabilities) {
        return capabilities.ports(SandboxPorts.class)
                .orElseThrow(() -> new IllegalArgumentException("Ports capability is not available"));
    }

    private SandboxArtifacts artifacts(SandboxRegistry.ProviderCapabilities capabilities) {
        return capabilities.artifacts(SandboxArtifacts.class)
                .orElseThrow(() -> new IllegalArgumentException("Artifacts capability is not available"));
    }

    private SandboxCommandResult runCommand(
            SandboxRegistry.ProviderCapabilities capabilities,
            String sessionId,
            String command,
            List<String> arguments,
            String workingDirectory,
            Map<String, String> environment,
            Duration timeout,
            Map<String, String> metadata) {
        return runCommand(terminal(capabilities), sessionId, command, arguments, workingDirectory, environment, timeout, metadata);
    }

    private SandboxCommandResult runCommand(
            SandboxTerminal terminal,
            String sessionId,
            String command,
            List<String> arguments,
            String workingDirectory,
            Map<String, String> environment,
            Duration timeout,
            Map<String, String> metadata) {
        return await(terminal.execute(sessionId, new SandboxCommand(
                command,
                arguments,
                workingDirectory,
                environment,
                timeout,
                false,
                metadata)));
    }

    private SandboxFile writeFile(
            SandboxRegistry.ProviderCapabilities capabilities,
            String sessionId,
            String path,
            byte[] content,
            String mediaType) {
        return await(fileSystem(capabilities).writeFile(sessionId, new SandboxFile(
                path,
                false,
                content.length,
                Instant.now(),
                mediaType,
                content,
                metadata("computer.wrapper", "workspace"))));
    }

    private Map<String, Object> runScrot(
            SandboxRegistry.ProviderCapabilities capabilities,
            SandboxTerminal terminal,
            String sessionId,
            String path,
            List<String> arguments,
            Map<String, Object> params,
            String wrapperName) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("path", path);
        List<String> safeArguments = arguments == null ? List.of() : arguments;
        String command = "mkdir -p -- " + shellQuote(parentPath(path))
                + " && scrot " + shellJoin(safeArguments) + (safeArguments.isEmpty() ? "" : " ")
                + shellQuote(path)
                + " && stat -c '%s' " + shellQuote(path);
        SandboxCommandResult result = runCommand(
                terminal,
                sessionId,
                command,
                List.of(),
                DEFAULT_WORKDIR,
                Map.of(),
                timeout(params, Duration.ofSeconds(30)),
                metadata("computer.wrapper", wrapperName));
        long sizeBytes = parseLong(result.stdout().trim(), 0L);
        output.put("command", commandResult(result));
        output.put("success", result.successful());
        output.put("sizeBytes", sizeBytes);
        if (result.successful() && bool(params, "registerArtifact", false)) {
            output.put("artifact", artifactMap(createArtifact(
                    capabilities,
                    sessionId,
                    text(params, "artifactId", "screenshot-" + Instant.now().toEpochMilli()),
                    text(params, "name", "Desktop screenshot"),
                    path,
                    "image/png",
                    sizeBytes,
                    null,
                    metadata("computer.wrapper", wrapperName))));
        }
        if (result.successful() && bool(params, "includeData", false)) {
            output.put("file", fileMap(readFile(capabilities, sessionId, path), true));
        }
        return output;
    }

    private SandboxFile readFile(SandboxRegistry.ProviderCapabilities capabilities, String sessionId, String path) {
        return await(fileSystem(capabilities).readFile(sessionId, path));
    }

    private long statPath(SandboxRegistry.ProviderCapabilities capabilities, String sessionId, String path) {
        SandboxCommandResult result = runCommand(
                capabilities,
                sessionId,
                "stat",
                List.of("-c", "%s", path),
                DEFAULT_WORKDIR,
                Map.of(),
                Duration.ofSeconds(20),
                metadata("computer.wrapper", "stat"));
        if (!result.successful()) {
            return 0L;
        }
        return parseLong(result.stdout().trim(), 0L);
    }

    private SandboxArtifact createArtifact(
            SandboxRegistry.ProviderCapabilities capabilities,
            String sessionId,
            String artifactId,
            String name,
            String path,
            String mediaType,
            long sizeBytes,
            URI uri,
            Map<String, String> metadata) {
        return await(artifacts(capabilities).createArtifact(sessionId, new SandboxArtifact(
                capabilities.providerId(),
                sessionId,
                artifactId,
                name,
                path,
                mediaType,
                sizeBytes,
                uri,
                Instant.now(),
                metadata)));
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(invocationTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("operation timed out after " + invocationTimeout, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("operation interrupted", ex);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    private static Map<String, Object> commandResult(SandboxCommandResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("providerId", result.providerId());
        output.put("sessionId", result.sessionId());
        output.put("commandId", result.commandId());
        output.put("exitCode", result.exitCode());
        output.put("stdout", result.stdout());
        output.put("stderr", result.stderr());
        output.put("successful", result.successful());
        output.put("metadata", result.metadata());
        result.failure().ifPresent(failure -> output.put("failure", failure));
        return output;
    }

    private static Map<String, Object> browserObservationMap(SandboxBrowserObservation observation, boolean includeScreenshot) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("providerId", observation.providerId());
        output.put("sessionId", observation.sessionId());
        output.put("url", observation.url());
        output.put("title", observation.title());
        output.put("text", observation.text());
        output.put("screenshotMediaType", observation.screenshotMediaType());
        output.put("screenshotBytes", observation.screenshot().length);
        if (includeScreenshot) {
            output.put("screenshotBase64", Base64.getEncoder().encodeToString(observation.screenshot()));
        }
        output.put("metadata", observation.metadata());
        return output;
    }

    private static Map<String, Object> displayFrameMap(SandboxDisplayFrame frame, boolean includeData) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("providerId", frame.providerId());
        output.put("sessionId", frame.sessionId());
        output.put("width", frame.width());
        output.put("height", frame.height());
        output.put("mediaType", frame.mediaType());
        output.put("dataBytes", frame.data().length);
        if (includeData) {
            output.put("dataBase64", Base64.getEncoder().encodeToString(frame.data()));
        }
        output.put("metadata", frame.metadata());
        return output;
    }

    private static Map<String, Object> fileMap(SandboxFile file, boolean includeContent) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("path", file.path());
        output.put("directory", file.directory());
        output.put("sizeBytes", file.sizeBytes());
        output.put("mediaType", file.mediaType());
        output.put("modifiedAt", file.modifiedAt());
        output.put("metadata", file.metadata());
        if (includeContent) {
            output.put("contentBase64", Base64.getEncoder().encodeToString(file.content()));
            if (isText(file.mediaType())) {
                output.put("contentText", new String(file.content(), StandardCharsets.UTF_8));
            }
        }
        return output;
    }

    private static Map<String, Object> artifactMap(SandboxArtifact artifact) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("providerId", artifact.providerId());
        output.put("sessionId", artifact.sessionId());
        output.put("artifactId", artifact.artifactId());
        output.put("name", artifact.name());
        output.put("path", artifact.path());
        output.put("mediaType", artifact.mediaType());
        output.put("sizeBytes", artifact.sizeBytes());
        output.put("uri", artifact.uri() == null ? "" : artifact.uri().toString());
        output.put("createdAt", artifact.createdAt());
        output.put("metadata", artifact.metadata());
        return output;
    }

    private static Map<String, Object> portMap(SandboxPort port) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("providerId", port.providerId());
        output.put("sessionId", port.sessionId());
        output.put("port", port.port());
        output.put("protocol", port.protocol());
        output.put("url", port.url() == null ? "" : port.url().toString());
        output.put("publicAccess", port.publicAccess());
        output.put("metadata", port.metadata());
        return output;
    }

    private static Map<String, Object> parseXDisplay(String stdout) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("display", "");
        output.put("width", 0);
        output.put("height", 0);
        output.put("dimensions", "");
        for (String line : stdout == null ? List.<String>of() : stdout.lines().toList()) {
            String[] parts = line.split("\\t", 2);
            if (parts.length != 2) {
                continue;
            }
            switch (parts[0]) {
                case "DISPLAY" -> output.put("display", parts[1]);
                case "GEOMETRY" -> {
                    String[] size = parts[1].trim().split("\\s+");
                    if (size.length >= 2) {
                        output.put("width", parseInt(size[0], 0));
                        output.put("height", parseInt(size[1], 0));
                    }
                }
                case "DIMENSIONS" -> output.put("dimensions", parts[1]);
                default -> {
                }
            }
        }
        return output;
    }

    private static Map<String, Object> firstPort(List<Map<String, Object>> ports, int portNumber) {
        return ports.stream()
                .filter(port -> Integer.toString(portNumber).equals(String.valueOf(port.get("port"))))
                .findFirst()
                .orElse(Map.of());
    }

    private static String sessionId(Map<String, Object> params) {
        return required(params, "sessionId");
    }

    private static String required(Map<String, Object> params, String key) {
        String value = text(params, key, "");
        if (!hasText(value)) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static List<String> requiredStringList(Map<String, Object> params, String key) {
        List<String> values = stringList(params.get(key));
        if (values.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return values;
    }

    private static String text(Map<String, Object> params, String key, String defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private static boolean bool(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static int integer(Map<String, Object> params, String key, int defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.toString());
    }

    private static long longValue(Map<String, Object> params, String key, long defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value.toString());
    }

    private static Duration timeout(Map<String, Object> params, Duration defaultTimeout) {
        long seconds = longValue(params, "timeoutSeconds", defaultTimeout.toSeconds());
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, String> converted = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                if (key != null && mapValue != null) {
                    converted.put(key.toString(), mapValue.toString());
                }
            });
            return Map.copyOf(converted);
        }
        return (Map<String, String>) value;
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .toList();
        }
        if (value.getClass().isArray()) {
            List<String> values = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                values.add(String.valueOf(java.lang.reflect.Array.get(value, i)));
            }
            return List.copyOf(values);
        }
        return List.of(value.toString());
    }

    private static byte[] contentBytes(Map<String, Object> params) {
        String contentBase64 = text(params, "contentBase64", "");
        if (hasText(contentBase64)) {
            return Base64.getDecoder().decode(contentBase64);
        }
        return text(params, "content", "").getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> metadata(String key, String value) {
        return Map.of(key, value);
    }

    private static String defaultArtifactId(Map<String, Object> params) {
        String path = text(params, "path", "artifact");
        String name = path.substring(path.lastIndexOf('/') + 1);
        return hasText(name) ? name : "artifact";
    }

    private static String defaultArtifactName(Map<String, Object> params) {
        return text(params, "name", defaultArtifactId(params));
    }

    private static URI uri(Object value) {
        String text = value == null ? "" : value.toString().trim();
        return text.isEmpty() ? null : URI.create(text);
    }

    private static String parentPath(String path) {
        int index = path.lastIndexOf('/');
        if (index <= 0) {
            return ".";
        }
        return path.substring(0, index);
    }

    private static Optional<String> regionArg(Map<String, Object> params) {
        Object value = params == null ? null : params.get("region");
        if (!(value instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        int x = intFromMap(map, "x", 0);
        int y = intFromMap(map, "y", 0);
        int width = intFromMap(map, "width", 0);
        int height = intFromMap(map, "height", 0);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("region.width and region.height must be positive");
        }
        return Optional.of(x + "," + y + "," + width + "," + height);
    }

    private static int intFromMap(Map<?, ?> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.toString());
    }

    private static String shellJoin(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(ComputerTool::shellQuote)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        if (value.matches("[A-Za-z0-9_./:=@%+-]+")) {
            return value;
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String mediaTypeForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }

    private static boolean hasCapability(Optional<?> capability) {
        return capability.isPresent();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean isText(String mediaType) {
        return mediaType != null
                && (mediaType.startsWith("text/")
                || mediaType.contains("json")
                || mediaType.contains("xml")
                || mediaType.contains("javascript"));
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
