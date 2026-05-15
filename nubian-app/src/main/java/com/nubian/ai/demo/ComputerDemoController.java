package com.nubian.ai.demo;

import com.nubian.ai.sandbox.api.SandboxDisplay;
import com.nubian.ai.sandbox.api.SandboxFileSystem;
import com.nubian.ai.sandbox.api.SandboxProvider;
import com.nubian.ai.sandbox.api.SandboxSessionService;
import com.nubian.ai.sandbox.model.SandboxDisplayFrame;
import com.nubian.ai.sandbox.model.SandboxFile;
import com.nubian.ai.sandbox.model.SandboxSession;
import com.nubian.ai.sandbox.registry.SandboxRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class ComputerDemoController {
    private static final Logger log = LoggerFactory.getLogger(ComputerDemoController.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);

    private final SandboxRegistry registry;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ComputerDemoController(SandboxRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/demo/computer")
    public String computerDemo(Model model) {
        model.addAttribute("selectedProviderId", selectedProviderId());
        return "demo/computer";
    }

    @GetMapping("/demo/api/computer/providers")
    @ResponseBody
    public Map<String, Object> providers() {
        return Map.of(
                "selectedProviderId", selectedProviderId(),
                "providers", registry.capabilities().values().stream()
                        .map(capabilities -> Map.of(
                                "providerId", capabilities.providerId(),
                                "session", capabilities.provider(SandboxProvider.class).isPresent(),
                                "terminal", capabilities.terminal().isPresent(),
                                "display", capabilities.display().isPresent(),
                                "files", capabilities.fileSystem().isPresent(),
                                "ports", capabilities.ports().isPresent(),
                                "computer", capabilities.computer().isPresent()))
                        .toList());
    }

    @GetMapping("/demo/api/computer/sessions")
    @ResponseBody
    public List<DemoSessionResponse> listDemoSessions(@RequestParam(required = false) String providerId) {
        String resolvedProviderId = providerId(providerId);
        return await(sessionService(resolvedProviderId).listSessions(Map.of())).stream()
                .map(DemoSessionResponse::from)
                .toList();
    }

    @PostMapping("/demo/api/computer/session")
    @ResponseBody
    public DemoSessionResponse createSession(@RequestBody(required = false) DemoSessionRequest request) {
        DemoSessionRequest safe = request == null ? new DemoSessionRequest(null, null, Map.of()) : request;
        String providerId = providerId(safe.providerId());
        Map<String, String> metadata = new LinkedHashMap<>(safe.metadata() == null ? Map.of() : safe.metadata());
        String requestedSessionId = firstNonBlank(safe.sessionId(), "demo-computer-" + UUID.randomUUID());
        Optional<SandboxSession> existing = await(sessionService(providerId).getSession(requestedSessionId));
        if (existing.isPresent()) {
            return DemoSessionResponse.from(existing.get());
        }
        metadata.putIfAbsent("firecracker.sessionId", requestedSessionId);
        SandboxSession session = await(sessionService(providerId).createSession(
                Map.of("demo", "manus-computer"),
                metadata));
        return DemoSessionResponse.from(session);
    }

    @PostMapping("/demo/api/computer/session/{providerId}/{sessionId}/resume")
    @ResponseBody
    public DemoSessionResponse resumeSession(@PathVariable String providerId, @PathVariable String sessionId) {
        return DemoSessionResponse.from(await(sessionService(providerId).startSession(sessionId)));
    }

    @PostMapping("/demo/api/computer/session/{providerId}/{sessionId}/suspend")
    @ResponseBody
    public DemoSessionResponse suspendSession(@PathVariable String providerId, @PathVariable String sessionId) {
        return DemoSessionResponse.from(await(sessionService(providerId).stopSession(sessionId)));
    }

    @GetMapping("/demo/api/computer/session/{providerId}/{sessionId}/screenshot")
    public ResponseEntity<byte[]> screenshot(@PathVariable String providerId, @PathVariable String sessionId) {
        SandboxDisplay display = capabilities(providerId).display(SandboxDisplay.class)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Sandbox display is not available"));
        SandboxDisplayFrame frame = await(display.captureFrame(sessionId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(frame.mediaType()))
                .body(frame.data());
    }

    @GetMapping("/demo/api/computer/session/{providerId}/{sessionId}/files/list")
    @ResponseBody
    public Map<String, Object> listFiles(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "/workspace") String path) {
        List<SandboxFile> files = await(fileSystem(providerId).listFiles(sessionId, path));
        return Map.of(
                "ok", true,
                "path", path,
                "entries", files.stream()
                        .map(file -> Map.of(
                                "name", fileName(file.path()),
                                "path", file.path(),
                                "type", file.directory() ? "directory" : "file",
                                "size", file.sizeBytes(),
                                "modified", file.modifiedAt() == null ? "" : file.modifiedAt().toString(),
                                "mediaType", file.mediaType()))
                        .toList());
    }

    @GetMapping("/demo/api/computer/session/{providerId}/{sessionId}/files")
    public ResponseEntity<byte[]> downloadFile(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestParam String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        SandboxFile file = await(fileSystem(providerId).readFile(sessionId, path));
        if (file.directory()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is a directory");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeFileName(fileName(file.path())) + "\"")
                .contentType(mediaType(file.mediaType()))
                .body(file.content());
    }

    @PostMapping("/demo/api/computer/session/{providerId}/{sessionId}/vnc-control")
    @ResponseBody
    public Map<String, Object> setVncControl(
            @PathVariable String providerId,
            @PathVariable String sessionId,
            @RequestBody(required = false) VncControlRequest request) {
        SandboxSession session = await(sessionService(providerId).getSession(sessionId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sandbox session not found: " + sessionId));
        boolean control = request != null && request.control();
        String baseUrl = flyVmAgentBaseUrl(session);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/vnc-control"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("{\"control\":" + control + "}"))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "FlyVM vnc-control returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return Map.of(
                    "ok", true,
                    "control", control,
                    "body", response.body() == null ? "" : response.body());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "VNC control interrupted", ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "VNC control failed: " + ex.getMessage(), ex);
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ex.getStatusCode().value());
        body.put("error", ex.getReason() == null ? ex.getMessage() : ex.getReason());
        body.put("exceptionClass", ex.getClass().getName());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    private SandboxSessionService sessionService(String providerId) {
        return capabilities(providerId).provider(SandboxProvider.class)
                .map(SandboxProvider::sessions)
                .or(() -> capabilities(providerId).session(SandboxSessionService.class))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Sandbox sessions are not available"));
    }

    private SandboxFileSystem fileSystem(String providerId) {
        return capabilities(providerId).fileSystem(SandboxFileSystem.class)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Sandbox files are not available"));
    }

    private SandboxRegistry.ProviderCapabilities capabilities(String providerId) {
        return registry.resolve(providerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sandbox provider not found: " + providerId));
    }

    private String providerId(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return selectedProviderId();
    }

    private String selectedProviderId() {
        return registry.selectedProviderId()
                .orElseGet(() -> registry.providerIds().stream().findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No sandbox provider registered")));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Sandbox operation timed out", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Sandbox operation interrupted", ex);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            String detail = cause.getClass().getName() + ": " + cause.getMessage();
            throw new ResponseStatusException(
                    sandboxErrorStatus(detail),
                    sandboxErrorReason(detail),
                    cause);
        }
    }

    private static HttpStatus sandboxErrorStatus(String detail) {
        return isExpiredComputerError(detail) ? HttpStatus.GONE : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String sandboxErrorReason(String detail) {
        if (isExpiredComputerError(detail)) {
            return "Computer session expired or VM no longer exists. Start a new computer.";
        }
        return detail;
    }

    private static boolean isExpiredComputerError(String detail) {
        String lower = String.valueOf(detail).toLowerCase(Locale.ROOT);
        return lower.contains("collection_not_found")
                || lower.contains("computer vm not found")
                || lower.contains("sandbox session not found");
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String flyVmAgentBaseUrl(SandboxSession session) {
        Map<String, String> metadata = session.metadata();
        String baseUrl = metadata.getOrDefault("flyvm.agentBaseUrl", "");
        if (baseUrl == null || baseUrl.isBlank()) {
            String apiBase = metadata.getOrDefault("flyvm.apiBaseUrl", "http://localhost:19191");
            String vmId = metadata.getOrDefault("flyvm.vmId", "");
            if (!vmId.isBlank()) {
                baseUrl = stripTrailingSlash(apiBase) + "/v1/computers/" + vmId;
            }
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "FlyVM agent base URL is unavailable");
        }
        return stripTrailingSlash(baseUrl);
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static MediaType mediaType(String value) {
        try {
            return value == null || value.isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(value);
        } catch (Exception ex) {
            log.warn("mediaType parse failed for value {}: {}", value, ex.toString());
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static String safeFileName(String value) {
        String safe = value == null || value.isBlank() ? "download" : value;
        return safe.replace("\"", "").replace("\\", "").replace("/", "");
    }

    public record DemoSessionRequest(String providerId, String sessionId, Map<String, String> metadata) {
    }

    public record VncControlRequest(boolean control) {
    }

    public record DemoSessionResponse(
            String providerId,
            String sessionId,
            String status,
            String vmId,
            String noVncUrl,
            String vncUrl,
            String agentBaseUrl,
            Map<String, String> metadata) {
        static DemoSessionResponse from(SandboxSession session) {
            Map<String, String> metadata = session.metadata();
            return new DemoSessionResponse(
                    session.providerId(),
                    session.sessionId(),
                    session.status().name(),
                    metadata.getOrDefault("flyvm.vmId", ""),
                    metadata.getOrDefault("flyvm.noVncUrl", ""),
                    metadata.getOrDefault("flyvm.vncUrl", ""),
                    metadata.getOrDefault("flyvm.agentBaseUrl", ""),
                    metadata);
        }
    }

}
