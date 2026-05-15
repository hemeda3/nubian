package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.api.SandboxComputer;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxCommandResult;
import com.nubian.ai.sandbox.model.SandboxComputerEnvironment;
import com.nubian.ai.sandbox.model.SandboxPort;
import com.nubian.ai.sandbox.model.SandboxSession;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirecrackerSandboxComputer implements SandboxComputer {
    private static final Logger log = LoggerFactory.getLogger(FirecrackerSandboxComputer.class);
    private static final List<ToolDefinition> TOOLS = List.of(
            new ToolDefinition("xvfb", "display", List.of("Xvfb")),
            new ToolDefinition("vnc", "display", List.of("x11vnc")),
            new ToolDefinition("novnc", "display", List.of("websockify")),
            new ToolDefinition("screenshot-scrot", "eyes", List.of("scrot")),
            new ToolDefinition("mouse-keyboard-xdotool", "hands", List.of("xdotool")),
            new ToolDefinition("browser-chromium", "browser", List.of("chromium", "chromium-browser", "google-chrome")),
            new ToolDefinition("python", "language", List.of("python3")),
            new ToolDefinition("pip", "language", List.of("pip3", "pip")),
            new ToolDefinition("node", "language", List.of("node")),
            new ToolDefinition("npm", "language", List.of("npm")),
            new ToolDefinition("pnpm", "language", List.of("pnpm")),
            new ToolDefinition("go", "language", List.of("go")),
            new ToolDefinition("git", "devtool", List.of("git")),
            new ToolDefinition("curl", "network", List.of("curl")),
            new ToolDefinition("wget", "network", List.of("wget")),
            new ToolDefinition("jq", "devtool", List.of("jq")),
            new ToolDefinition("gcc", "build", List.of("gcc")),
            new ToolDefinition("gpp", "build", List.of("g++")),
            new ToolDefinition("make", "build", List.of("make")),
            new ToolDefinition("unzip", "archive", List.of("unzip")),
            new ToolDefinition("tar", "archive", List.of("tar")),
            new ToolDefinition("zip", "archive", List.of("zip")),
            new ToolDefinition("ffmpeg", "media", List.of("ffmpeg")),
            new ToolDefinition("inkscape", "graphics", List.of("inkscape")),
            new ToolDefinition("poppler-text", "pdf", List.of("pdftotext")),
            new ToolDefinition("poppler-image", "pdf", List.of("pdftoppm")),
            new ToolDefinition("tesseract", "ocr", List.of("tesseract")),
            new ToolDefinition("sqlite", "database", List.of("sqlite3")));
    private static final List<DirectoryDefinition> DIRECTORIES = List.of(
            new DirectoryDefinition("workspace", "/workspace", "user files and generated output"),
            new DirectoryDefinition("downloads", "/downloads", "browser downloads"),
            new DirectoryDefinition("uploads", "/uploads", "user uploaded files"),
            new DirectoryDefinition("logs", "/logs", "screenshots, actions, and terminal output"),
            new DirectoryDefinition("agent-tmp", "/tmp/agent", "scratchpad, screenshots, browser DOM, and OCR"));

    private final String providerId;
    private final FirecrackerSandboxSessionService sessions;
    private final FirecrackerSandboxPorts ports;

    public FirecrackerSandboxComputer(
            FirecrackerSandboxSessionService sessions,
            FirecrackerSandboxPorts ports) {
        this(FirecrackerSandboxProvider.PROVIDER_ID, sessions, ports);
    }

    public FirecrackerSandboxComputer(
            String providerId,
            FirecrackerSandboxSessionService sessions,
            FirecrackerSandboxPorts ports) {
        this.providerId = providerId;
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.ports = java.util.Objects.requireNonNull(ports, "ports");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("runtime", "firecracker", "contract", "flyvm-computer");
    }

    @Override
    public CompletableFuture<SandboxComputerEnvironment> inspect(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            SandboxSession session = sessions.requireRunningSession(sessionId, "computer.inspect").orElseThrow();
            SandboxCommandResult result = sessions.flyVmClient().execute(
                    sessionId,
                    session.metadata().get("flyvm.agentBaseUrl"),
                    new SandboxCommand(
                            inspectScript(),
                            List.of(),
                            "/workspace",
                            Map.of(),
                            Duration.ofSeconds(30),
                            false,
                            Map.of("sandbox.operation", "computer.inspect")));
            Map<String, String> probe = parseProbe(result.stdout());
            List<SandboxPort> exposedPorts = ports.listPorts(sessionId).join();
            return environment(session, probe, exposedPorts);
        });
    }

    private SandboxComputerEnvironment environment(
            SandboxSession session,
            Map<String, String> probe,
            List<SandboxPort> exposedPorts) {
        List<SandboxComputerEnvironment.Tool> tools = new ArrayList<>();
        Map<String, Boolean> availableTools = new LinkedHashMap<>();
        for (ToolDefinition tool : TOOLS) {
            String detectedCommand = firstDetected(tool, probe);
            boolean available = !detectedCommand.isBlank();
            availableTools.put(tool.id(), available);
            tools.add(new SandboxComputerEnvironment.Tool(
                    tool.id(),
                    tool.category(),
                    tool.commands(),
                    available,
                    detectedCommand,
                    probe.getOrDefault("tool.path." + tool.id(), ""),
                    probe.getOrDefault("tool.version." + tool.id(), ""),
                    Map.of()));
        }

        List<SandboxComputerEnvironment.Directory> directories = DIRECTORIES.stream()
                .map(directory -> new SandboxComputerEnvironment.Directory(
                        directory.id(),
                        directory.path(),
                        directory.purpose(),
                        "true".equals(probe.get("dir." + directory.path())),
                        Map.of()))
                .toList();
        List<SandboxComputerEnvironment.Endpoint> endpoints = endpoints(exposedPorts);
        return new SandboxComputerEnvironment(
                providerId,
                session.sessionId(),
                Instant.now(),
                "flyvm-computer",
                probe.getOrDefault("os", "linux"),
                features(availableTools, directories, endpoints),
                tools,
                directories,
                endpoints,
                new SandboxComputerEnvironment.ResourceLimits(
                        session.metadata().getOrDefault("flyvm.vcpu", ""),
                        session.metadata().getOrDefault("flyvm.memMib", ""),
                        "",
                        "",
                        "",
                        session.metadata().getOrDefault("flyvm.dataDiskMib", ""),
                        "server-nat",
                        Map.of("flyvm.hostId", session.metadata().getOrDefault("flyvm.hostId", ""))),
                session.metadata());
    }

    private List<SandboxComputerEnvironment.Feature> features(
            Map<String, Boolean> tools,
            List<SandboxComputerEnvironment.Directory> directories,
            List<SandboxComputerEnvironment.Endpoint> endpoints) {
        boolean workspace = directories.stream().allMatch(SandboxComputerEnvironment.Directory::available);
        boolean display = tools.getOrDefault("xvfb", false) && endpoint(endpoints, "novnc");
        boolean screenshots = tools.getOrDefault("screenshot-scrot", false);
        boolean hands = tools.getOrDefault("mouse-keyboard-xdotool", false);
        boolean languages = tools.getOrDefault("python", false)
                && tools.getOrDefault("node", false)
                && tools.getOrDefault("go", false);
        return List.of(
                feature("eyes", "computer", "Screenshots and visual capture", screenshots, "scrot=" + screenshots),
                feature("hands", "computer", "Mouse, keyboard, shell, and file control", hands, "xdotool=" + hands),
                feature("brain", "computer", "Python, Node, Go, native build, and scripting toolchains", languages, "python/node/go=" + languages),
                feature("graphics", "computer", "SVG/vector conversion and visual asset generation", tools.getOrDefault("inkscape", false), "inkscape=" + tools.getOrDefault("inkscape", false)),
                feature("memory", "computer", "Workspace, downloads, uploads, logs, and scratch folders", workspace, "all directories present=" + workspace),
                feature("display", "computer", "X display plus VNC/noVNC desktop viewer", display, "novnc endpoint=" + endpoint(endpoints, "novnc")),
                feature("browser", "computer", "Chromium available inside the desktop", tools.getOrDefault("browser-chromium", false), "chromium=" + tools.getOrDefault("browser-chromium", false)),
                feature("internet", "network", "Outbound network access from the VM", true, "FlyVM guest NAT"),
                feature("guard", "security", "Firecracker microVM isolation plus FlyVM authenticated REST proxy", true, "no SSH fallback"));
    }

    private static SandboxComputerEnvironment.Feature feature(
            String id,
            String category,
            String name,
            boolean available,
            String detail) {
        return new SandboxComputerEnvironment.Feature(id, category, name, available, detail, Map.of());
    }

    private static List<SandboxComputerEnvironment.Endpoint> endpoints(List<SandboxPort> ports) {
        return ports.stream()
                .map(port -> {
                    String id = switch (port.port()) {
                        case 6080 -> "novnc";
                        case 5900 -> "vnc";
                        case 6090 -> "guest-agent-api";
                        default -> "port-" + port.port();
                    };
                    return new SandboxComputerEnvironment.Endpoint(
                            id,
                            port.protocol(),
                            port.url() == null ? "" : port.url().toString(),
                            port.port(),
                            parseInteger(port.metadata().get("flyvm.localPort")),
                            true,
                            port.metadata());
                })
                .toList();
    }

    private static boolean endpoint(List<SandboxComputerEnvironment.Endpoint> endpoints, String id) {
        return endpoints.stream().anyMatch(endpoint -> id.equals(endpoint.id()) && endpoint.available());
    }

    private static String inspectScript() {
        StringBuilder builder = new StringBuilder();
        builder.append("printf 'os=%s\\n' \"$(uname -a)\"\n");
        for (DirectoryDefinition directory : DIRECTORIES) {
            builder.append("[ -d ")
                    .append(FlyVmComputerClient.shellQuote(directory.path()))
                    .append(" ] && echo dir.")
                    .append(directory.path())
                    .append("=true || echo dir.")
                    .append(directory.path())
                    .append("=false\n");
        }
        for (ToolDefinition tool : TOOLS) {
            builder.append("for c in");
            for (String command : tool.commands()) {
                builder.append(' ').append(FlyVmComputerClient.shellQuote(command));
            }
            builder.append("; do if command -v \"$c\" >/dev/null 2>&1; then ")
                    .append("echo tool.")
                    .append(tool.id())
                    .append("=$c; echo tool.path.")
                    .append(tool.id())
                    .append("=$(command -v \"$c\"); \"$c\" --version 2>/dev/null | head -1 | sed 's/^/tool.version.")
                    .append(tool.id())
                    .append("=/'; break; fi; done\n");
        }
        return builder.toString();
    }

    private static Map<String, String> parseProbe(String stdout) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : stdout == null ? List.<String>of() : stdout.lines().toList()) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            values.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return values;
    }

    private static String firstDetected(ToolDefinition tool, Map<String, String> probe) {
        return probe.getOrDefault("tool." + tool.id(), "");
    }

    private static Integer parseInteger(String value) {
        try {
            return value == null || value.isBlank() ? null : Integer.parseInt(value);
        } catch (RuntimeException ex) {
            log.debug("parseInteger fallback: {}", ex.toString());
            return null;
        }
    }

    private record ToolDefinition(String id, String category, List<String> commands) {
    }

    private record DirectoryDefinition(String id, String path, String purpose) {
    }
}
