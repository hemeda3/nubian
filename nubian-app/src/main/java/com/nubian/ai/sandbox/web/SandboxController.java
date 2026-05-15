package com.nubian.ai.sandbox.web;

import com.nubian.ai.sandbox.api.SandboxArtifacts;
import com.nubian.ai.sandbox.api.SandboxBrowser;
import com.nubian.ai.sandbox.api.SandboxComputer;
import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.api.SandboxPorts;
import com.nubian.ai.sandbox.api.SandboxProvider;
import com.nubian.ai.sandbox.api.SandboxSessionService;
import com.nubian.ai.sandbox.api.SandboxTerminal;
import com.nubian.ai.sandbox.model.SandboxArtifact;
import com.nubian.ai.sandbox.model.SandboxBrowserAction;
import com.nubian.ai.sandbox.model.SandboxBrowserObservation;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxComputerEnvironment;
import com.nubian.ai.sandbox.model.SandboxDisplayFrame;
import com.nubian.ai.sandbox.model.SandboxFile;
import com.nubian.ai.sandbox.model.SandboxPort;
import com.nubian.ai.sandbox.model.SandboxSession;
import com.nubian.ai.sandbox.registry.SandboxRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;

    private final SandboxRegistry registry;

    public SandboxController(SandboxRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        return Map.of(
                "selectedProviderId", registry.selectedProviderId().orElse(""),
                "providers", registry.capabilities().values().stream()
                        .map(this::providerSummary)
                        .toList());
    }

    @PostMapping("/sessions")
    public SandboxSession createSession(
            @RequestParam(value = "providerId", required = false) String providerId,
            @RequestBody(required = false) CreateSessionRequest request) {
        CreateSessionRequest safeRequest = request == null
                ? new CreateSessionRequest(Map.of(), Map.of())
                : request;
        return await(
                sessions(providerId).createSession(nonNull(safeRequest.labels()), nonNull(safeRequest.metadata())),
                "create sandbox session");
    }

    @GetMapping("/sessions/{providerId}/{sessionId}")
    public SandboxSession getSession(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        Optional<SandboxSession> session = await(sessions(providerId).getSession(sessionId), "get sandbox session");
        return session.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sandbox session not found"));
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/start")
    public SandboxSession startSession(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        return await(sessions(providerId).startSession(sessionId), "start sandbox session");
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/resume")
    public SandboxSession resumeSession(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        return await(sessions(providerId).startSession(sessionId), "resume sandbox session");
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/stop")
    public SandboxSession stopSession(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        return await(sessions(providerId).stopSession(sessionId), "stop sandbox session");
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/suspend")
    public SandboxSession suspendSession(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        return await(sessions(providerId).stopSession(sessionId), "suspend sandbox session");
    }

    @DeleteMapping("/sessions/{providerId}/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        await(sessions(providerId).deleteSession(sessionId), "delete sandbox session");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/terminal")
    public Object executeCommand(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestBody ExecuteCommandRequest request) {
        if (request == null || request.command() == null || request.command().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command is required");
        }
        SandboxCommand command = new SandboxCommand(
                request.command(),
                request.arguments() == null ? List.of() : request.arguments(),
                request.workingDirectory() == null ? "" : request.workingDirectory(),
                nonNull(request.environment()),
                Duration.ofSeconds(request.timeoutSeconds() == null ? 30 : Math.max(1, request.timeoutSeconds())),
                Boolean.TRUE.equals(request.interactive()),
                nonNull(request.metadata()));
        return await(terminal(providerId).execute(sessionId, command), "execute sandbox command");
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/files")
    public SandboxFile writeFile(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestBody WriteFileRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        byte[] content = Boolean.TRUE.equals(request.base64())
                ? Base64.getDecoder().decode(request.content() == null ? "" : request.content())
                : (request.content() == null ? "" : request.content()).getBytes(StandardCharsets.UTF_8);
        SandboxFile file = new SandboxFile(
                request.path(),
                false,
                content.length,
                Instant.now(),
                request.mediaType() == null ? "text/plain" : request.mediaType(),
                content,
                nonNull(request.metadata()));
        return await(fileSystem(providerId).writeFile(sessionId, file), "write sandbox file");
    }

    @GetMapping("/sessions/{providerId}/{sessionId}/files")
    public Object readOrListFiles(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "/") String path,
            @RequestParam(defaultValue = "false") boolean list) {
        SandboxFileSystem fileSystem = fileSystem(providerId);
        if (list) {
            return await(fileSystem.listFiles(sessionId, path), "list sandbox files");
        }
        return await(fileSystem.readFile(sessionId, path), "read sandbox file");
    }

    @GetMapping("/sessions/{providerId}/{sessionId}/ports")
    public List<SandboxPort> listPorts(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        return await(ports(providerId).listPorts(sessionId), "list sandbox ports");
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/browser")
    public SandboxBrowserObservation browserAction(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestBody BrowserActionRequest request) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is required");
        }
        SandboxBrowserAction action = new SandboxBrowserAction(
                parseBrowserActionType(request.action()),
                nonNull(request.parameters()),
                request.timeoutSeconds() == null
                        ? null
                        : Duration.ofSeconds(Math.max(1, request.timeoutSeconds())),
                nonNull(request.metadata()));
        return await(browser(providerId).performAction(sessionId, action), "perform sandbox browser action");
    }

    @GetMapping("/sessions/{providerId}/{sessionId}/browser")
    public SandboxBrowserObservation observeBrowser(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        return await(browser(providerId).observe(sessionId), "observe sandbox browser");
    }

    @GetMapping("/sessions/{providerId}/{sessionId}/display")
    public SandboxDisplayFrame captureDisplay(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        return await(display(providerId).captureFrame(sessionId), "capture sandbox display");
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/display")
    public ResponseEntity<Void> resizeDisplay(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestBody ResizeDisplayRequest request) {
        if (request == null || request.width() == null || request.height() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "width and height are required");
        }
        await(display(providerId).resizeDisplay(sessionId, request.width(), request.height()), "resize sandbox display");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{providerId}/{sessionId}/artifacts")
    public SandboxArtifact createArtifact(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestBody CreateArtifactRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "artifact request is required");
        }
        SandboxArtifact artifact = new SandboxArtifact(
                providerId,
                sessionId,
                request.artifactId() == null ? "" : request.artifactId(),
                request.name() == null ? "" : request.name(),
                request.path() == null ? "" : request.path(),
                request.mediaType() == null ? "application/octet-stream" : request.mediaType(),
                request.sizeBytes() == null ? 0L : request.sizeBytes(),
                request.uri() == null || request.uri().isBlank() ? null : URI.create(request.uri()),
                Instant.now(),
                nonNull(request.metadata()));
        return await(artifacts(providerId).createArtifact(sessionId, artifact), "create sandbox artifact");
    }

    @GetMapping("/sessions/{providerId}/{sessionId}/artifacts")
    public Object listOrGetArtifacts(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestParam(required = false) String artifactId) {
        SandboxArtifacts sandboxArtifacts = artifacts(providerId);
        if (artifactId == null || artifactId.isBlank()) {
            return await(sandboxArtifacts.listArtifacts(sessionId), "list sandbox artifacts");
        }
        return await(sandboxArtifacts.getArtifact(sessionId, artifactId), "get sandbox artifact")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sandbox artifact not found"));
    }

    @DeleteMapping("/sessions/{providerId}/{sessionId}/artifacts/{artifactId}")
    public ResponseEntity<Void> deleteArtifact(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @PathVariable String artifactId) {
        await(artifacts(providerId).deleteArtifact(sessionId, artifactId), "delete sandbox artifact");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{providerId}/{sessionId}/computer")
    public SandboxComputerEnvironment inspectComputer(
            @PathVariable String providerId,
            @PathVariable String sessionId) {
        return await(computer(providerId).inspect(sessionId), "inspect sandbox computer");
    }

    private Map<String, Object> providerSummary(SandboxRegistry.ProviderCapabilities capabilities) {
        return Map.of(
                "providerId", capabilities.providerId(),
                "session", capabilities.provider(SandboxProvider.class).isPresent(),
                "files", capabilities.fileSystem(SandboxFileSystem.class).isPresent(),
                "terminal", capabilities.terminal(SandboxTerminal.class).isPresent(),
                "ports", capabilities.ports(SandboxPorts.class).isPresent(),
                "browser", capabilities.browser().isPresent(),
                "display", capabilities.display().isPresent(),
                "artifacts", capabilities.artifacts().isPresent(),
                "computer", capabilities.computer().isPresent());
    }

    private SandboxSessionService sessions(String providerId) {
        return capabilities(providerId)
                .provider(SandboxProvider.class)
                .map(SandboxProvider::sessions)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Sandbox session capability is not available for provider"));
    }

    private SandboxTerminal terminal(String providerId) {
        return capabilities(providerId)
                .terminal(SandboxTerminal.class)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Sandbox terminal capability is not available for provider"));
    }

    private SandboxFileSystem fileSystem(String providerId) {
        return capabilities(providerId)
                .fileSystem(SandboxFileSystem.class)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Sandbox file capability is not available for provider"));
    }

    private SandboxPorts ports(String providerId) {
        return capabilities(providerId)
                .ports(SandboxPorts.class)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Sandbox ports capability is not available for provider"));
    }

    private SandboxBrowser browser(String providerId) {
        return capabilities(providerId)
                .browser(SandboxBrowser.class)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Sandbox browser capability is not available for provider"));
    }

    private SandboxDisplay display(String providerId) {
        return capabilities(providerId)
                .display(SandboxDisplay.class)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Sandbox display capability is not available for provider"));
    }

    private SandboxArtifacts artifacts(String providerId) {
        return capabilities(providerId)
                .artifacts(SandboxArtifacts.class)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Sandbox artifact capability is not available for provider"));
    }

    private SandboxComputer computer(String providerId) {
        return capabilities(providerId)
                .computer(SandboxComputer.class)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Sandbox computer capability is not available for provider"));
    }

    private SandboxRegistry.ProviderCapabilities capabilities(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return registry.selectedCapabilities().orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No sandbox providerId supplied and no selected provider configured"));
        }
        return registry.resolve(providerId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Sandbox provider not found: " + providerId));
    }

    private static <T> T await(CompletableFuture<T> future, String operation) {
        try {
            return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, operation + " timed out", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, operation + " interrupted", ex);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, operation + " failed: " + cause.getMessage(), cause);
        }
    }

    private static Map<String, String> nonNull(Map<String, String> map) {
        return map == null ? Map.of() : map;
    }

    private static SandboxBrowserAction.Type parseBrowserActionType(String action) {
        try {
            return SandboxBrowserAction.Type.valueOf(action.trim().replace('-', '_').toUpperCase());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported browser action: " + action, ex);
        }
    }

    public record CreateSessionRequest(Map<String, String> labels, Map<String, String> metadata) {
    }

    public record ExecuteCommandRequest(
            String command,
            List<String> arguments,
            String workingDirectory,
            Map<String, String> environment,
            Long timeoutSeconds,
            Boolean interactive,
            Map<String, String> metadata) {
    }

    public record WriteFileRequest(
            String path,
            String content,
            Boolean base64,
            String mediaType,
            Map<String, String> metadata) {
    }

    public record BrowserActionRequest(
            String action,
            Map<String, String> parameters,
            Long timeoutSeconds,
            Map<String, String> metadata) {
    }

    public record ResizeDisplayRequest(Integer width, Integer height) {
    }

    public record CreateArtifactRequest(
            String artifactId,
            String name,
            String path,
            String mediaType,
            Long sizeBytes,
            String uri,
            Map<String, String> metadata) {
    }
}
