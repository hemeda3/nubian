package com.nubian.ai.sandbox.firecracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.sandbox.model.SandboxCommand;
import com.nubian.ai.sandbox.model.SandboxCommandResult;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FlyVmComputerClient {
    private static final Logger log = LoggerFactory.getLogger(FlyVmComputerClient.class);
    private final FirecrackerSandboxProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;

    FlyVmComputerClient(
            FirecrackerSandboxProperties properties,
            ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    FlyVmComputerClient(
            FirecrackerSandboxProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getGuestHttpTimeout())
                .build();
    }

    ProvisionedComputer provision(String tenantId, Map<String, String> metadata) {
        return provisionViaRest(tenantId, metadata);
    }

    private ProvisionedComputer provisionViaRest(String tenantId, Map<String, String> metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mem_mib", intValue(metadata, "flyvm.memMib", properties.getMemoryMib()));
        payload.put("vcpu", intValue(metadata, "flyvm.vcpu", properties.getVcpu()));
        payload.put("data_disk_mib", intValue(metadata, "flyvm.dataDiskMib", properties.getDataDiskMib()));
        putIfHasText(payload, "region", firstNonBlank(metadata.get("flyvm.region"), properties.getRegion()));
        putIfHasText(payload, "required_provider", firstNonBlank(
                metadata.get("flyvm.requiredProvider"),
                metadata.get("flyvm.required_provider"),
                properties.getRequiredProvider()));

        HttpRequest request = controlPlaneRequest("/v1/computers", properties.getProvisionTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(json(payload)))
                .build();
        HttpResponse<String> response = sendString(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw restFailure(
                    tenantId,
                    "flyvm.provision",
                    "FlyVM REST computer provision failed",
                    response);
        }
        return parseComputerResponse(tenantId, "flyvm.provision", response.body());
    }

    SessionEndpoints openEndpoints(String sessionId, ProvisionedComputer computer) {
        if (!hasText(computer.vmId())) {
            throw endpointFailure(sessionId, "vm_id", "FlyVM REST response is missing vm_id", "");
        }
        String proxyBaseUrl = properties.getApiBaseUrl() + "/v1/computers/" + computer.vmId();
        String noVncUrl = firstNonBlank(
                properties.getDirectNoVncUrl(),
                absoluteApiUrl(firstNonBlank(computer.proxyVncUrl(), proxyBaseUrl + "/vnc")));
        return new SessionEndpoints(
                proxyBaseUrl,
                noVncUrl,
                noVncUrl,
                properties.getGuestNoVncPort(),
                properties.getGuestVncPort(),
                properties.getGuestAgentPort());
    }

    void waitForHealth(String sessionId, String baseUrl) {
        Instant deadline = Instant.now().plus(properties.getHealthTimeout());
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                health(baseUrl);
                return;
            } catch (RuntimeException ex) {
                last = ex;
                sleep(Duration.ofMillis(300));
            }
        }
        throw new FirecrackerSandboxException(new SandboxFailure(
                FirecrackerSandboxProvider.PROVIDER_ID,
                sessionId,
                SandboxFailureCode.PROVIDER_UNAVAILABLE,
                "FlyVM guest agent did not become healthy" + (last == null ? "" : ": " + last.getMessage()),
                "flyvm.health",
                true,
                Map.of("baseUrl", baseUrl)));
    }

    void ensureNoVncProxy(String sessionId, String baseUrl) {
        String command = """
                set -e
                if command -v ss >/dev/null 2>&1; then
                  pids="$(ss -ltnp 'sport = :6080' 2>/dev/null | sed -n 's/.*pid=\\([0-9][0-9]*\\).*/\\1/p' | sort -u)"
                  for pid in $pids; do
                    kill "$pid" >/dev/null 2>&1 || true
                  done
                  sleep 1
                fi
                if ! ss -ltnp 2>/dev/null | grep -q ':5900'; then
                  echo "x11vnc is not listening on 5900" >&2
                  exit 1
                fi
                nohup /usr/bin/websockify --web=/usr/share/novnc/ 0.0.0.0:6080 127.0.0.1:5900 > /logs/novnc.log 2>&1 &
                sleep 1
                ps -ef | grep '[w]ebsockify'
                tail -20 /logs/novnc.log || true
                """;
        SandboxCommandResult result = execute(
                sessionId,
                baseUrl,
                new SandboxCommand(
                        command,
                        List.of(),
                        "/logs",
                        Map.of(),
                        Duration.ofSeconds(30),
                        false,
                        Map.of("flyvm.repair", "novnc-proxy")));
        if (!result.successful()) {
            throw new FirecrackerSandboxException(new SandboxFailure(
                    FirecrackerSandboxProvider.PROVIDER_ID,
                    sessionId,
                    SandboxFailureCode.PROVIDER_UNAVAILABLE,
                    "Failed to ensure noVNC proxy targets 127.0.0.1:5900: "
                            + firstNonBlank(result.stderr(), result.stdout(), "exit " + result.exitCode()),
                    "flyvm.novnc.repair",
                    true,
                    Map.of("stdout", result.stdout(), "stderr", result.stderr())));
        }
    }

    Map<String, String> health(String baseUrl) {
        HttpRequest request = proxyRequest(baseUrl, "/health", properties.getGuestHttpTimeout())
                .timeout(properties.getGuestHttpTimeout())
                .GET()
                .build();
        HttpResponse<String> response = sendString(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("health returned HTTP " + response.statusCode());
        }
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(response.body());
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        } catch (Exception ex) {
            log.debug("checkHealth fallback: {}", ex.toString());
            result.put("raw", response.body());
        }
        return result;
    }

    SandboxCommandResult execute(String sessionId, String baseUrl, SandboxCommand command) {
        Instant startedAt = Instant.now();
        String shellCommand = shellCommand(command);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cwd", hasText(command.workingDirectory()) ? command.workingDirectory() : "/workspace");
        payload.put("timeout_ms", timeoutMillis(command.timeout()));
        payload.put("cmd", shellCommand);
        HttpRequest request = proxyRequest(baseUrl, "/exec", command.timeout() == null
                        ? properties.getGuestHttpTimeout()
                        : command.timeout().plusSeconds(5))
                .timeout(command.timeout() == null ? properties.getGuestHttpTimeout() : command.timeout().plusSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(payload)))
                .build();
        try {
            HttpResponse<String> response = sendString(request);
            Instant completedAt = Instant.now();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                SandboxFailure failure = new SandboxFailure(
                        FirecrackerSandboxProvider.PROVIDER_ID,
                        sessionId,
                        SandboxFailureCode.COMMAND_ERROR,
                        "FlyVM guest exec returned HTTP " + response.statusCode(),
                        "terminal.execute",
                        true,
                        Map.of("body", response.body()));
                return new SandboxCommandResult(
                        FirecrackerSandboxProvider.PROVIDER_ID,
                        sessionId,
                        UUID.randomUUID().toString(),
                        -1,
                        "",
                        response.body(),
                        startedAt,
                        completedAt,
                        Optional.of(failure),
                        commandMetadata(command, shellCommand, baseUrl));
            }
            return parseCommandResult(sessionId, response.body(), startedAt, completedAt, command, shellCommand, baseUrl);
        } catch (RuntimeException ex) {
            Instant completedAt = Instant.now();
            SandboxFailure failure = new SandboxFailure(
                    FirecrackerSandboxProvider.PROVIDER_ID,
                    sessionId,
                    SandboxFailureCode.COMMAND_ERROR,
                    ex.getMessage(),
                    "terminal.execute",
                    true,
                    Map.of("cmd", shellCommand));
            return new SandboxCommandResult(
                    FirecrackerSandboxProvider.PROVIDER_ID,
                    sessionId,
                    UUID.randomUUID().toString(),
                    -1,
                    "",
                    ex.getMessage(),
                    startedAt,
                    completedAt,
                    Optional.of(failure),
                    commandMetadata(command, shellCommand, baseUrl));
        }
    }

    byte[] screenshot(String baseUrl) {
        HttpRequest request = proxyRequest(baseUrl, "/screenshot", properties.getGuestHttpTimeout())
                .timeout(properties.getGuestHttpTimeout())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("screenshot returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    HttpResponse<byte[]> guestFile(String baseUrl, String path) {
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);
        HttpRequest request = proxyRequest(baseUrl, "/files?path=" + encodedPath, properties.getGuestHttpTimeout())
                .timeout(properties.getGuestHttpTimeout())
                .header("Accept", "*/*")
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    JsonNode guestFilesList(String baseUrl, String path) {
        String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);
        HttpRequest request = proxyRequest(baseUrl, "/files/list?path=" + encodedPath, properties.getGuestHttpTimeout())
                .timeout(properties.getGuestHttpTimeout())
                .GET()
                .build();
        HttpResponse<String> response = sendString(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("files/list returned HTTP " + response.statusCode() + ": " + response.body());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("files/list returned invalid JSON: " + ex.getMessage(), ex);
        }
    }

    void suspend(String sessionId, String vmId) {
        restLifecycleCall(sessionId, vmId, "POST", "/v1/computers/" + vmId + "/suspend", "flyvm.suspend");
    }

    void destroy(String sessionId, String vmId) {
        restLifecycleCall(sessionId, vmId, "DELETE", "/v1/computers/" + vmId, "flyvm.destroy");
    }

    /**
     * GET /v1/computers/{vmId} — returns the raw JSON body, or null when the server
     * returns 404.  Any other non-2xx status throws {@link IllegalStateException}.
     */
    String getComputer(String vmId) {
        HttpRequest request = controlPlaneRequest("/v1/computers/" + vmId, properties.getLifecycleTimeout())
                .GET()
                .build();
        HttpResponse<String> response = sendString(request);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "FlyVM GET /v1/computers/" + vmId + " returned HTTP " + response.statusCode()
                            + ": " + response.body());
        }
        return response.body();
    }

    /**
     * GET /v1/computers — returns the list of computers as a {@link JsonNode} array,
     * never null.  Throws {@link IllegalStateException} on non-2xx.
     */
    List<JsonNode> listComputers() {
        HttpRequest request = controlPlaneRequest("/v1/computers", properties.getLifecycleTimeout())
                .GET()
                .build();
        HttpResponse<String> response = sendString(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "FlyVM GET /v1/computers returned HTTP " + response.statusCode()
                            + ": " + response.body());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            // API may return {"computers":[...]} or a bare array
            JsonNode array = root.isArray() ? root : root.path("computers");
            if (!array.isArray()) {
                throw new IllegalStateException(
                        "FlyVM GET /v1/computers returned unexpected JSON shape: " + response.body());
            }
            List<JsonNode> result = new ArrayList<>();
            array.forEach(result::add);
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "FlyVM GET /v1/computers returned unparseable body: " + ex.getMessage(), ex);
        }
    }

    private ProvisionedComputer parseComputerResponse(String sessionId, String operation, String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String tenantId = text(node, "tenant_id", text(node, "tenantId", ""));
            String vmId = text(node, "vm_id", text(node, "vmId", ""));
            String hostId = text(node, "host_id", text(node, "hostId", ""));
            String state = text(node, "state", "");
            String serviceType = text(node, "service_type", text(node, "serviceType", ""));
            String vsockPath = text(node, "vsock_path", text(node, "vsockPath", ""));
            String apiAddr = text(node, "api_addr", text(node, "apiAddr", ""));
            String agentApiAddr = text(node, "agent_api_addr", text(node, "agentApiAddr", ""));
            String noVncUrl = nestedText(node, "novnc", "url", "");
            String vncUrl = normalizeVncUrl(nestedText(node, "vnc", "url", ""));
            String proxyVncUrl = nestedText(node, "proxy_urls", "vnc_url", "");
            String guestHost = firstNonBlank(
                    uriHost(agentApiAddr),
                    uriHost(apiAddr),
                    nestedText(node, "novnc", "host", ""),
                    nestedText(node, "vnc", "host", ""));
            if (!hasText(vmId)) {
                throw new IllegalStateException("FlyVM REST response did not include vm_id");
            }
            return new ProvisionedComputer(
                    tenantId,
                    vmId,
                    hostId,
                    state,
                    serviceType,
                    vsockPath,
                    apiAddr,
                    agentApiAddr,
                    noVncUrl,
                    vncUrl,
                    proxyVncUrl,
                    guestHost);
        } catch (Exception ex) {
            throw new FirecrackerSandboxException(new SandboxFailure(
                    FirecrackerSandboxProvider.PROVIDER_ID,
                    sessionId,
                    SandboxFailureCode.PROVIDER_UNAVAILABLE,
                    "Failed to parse FlyVM REST computer response: " + ex.getMessage(),
                    operation,
                    true,
                    Map.of("body", body)));
        }
    }

    private void restLifecycleCall(String sessionId, String vmId, String method, String path, String operation) {
        if (!hasText(vmId)) {
            return;
        }
        HttpRequest.Builder builder = controlPlaneRequest(path, properties.getLifecycleTimeout());
        if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString("{}"));
        }
        HttpResponse<String> response = sendString(builder.build());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw restFailure(sessionId, operation, "FlyVM REST lifecycle call failed", response);
        }
    }

    private HttpRequest.Builder controlPlaneRequest(String path, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getApiBaseUrl() + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        String bearerToken = bearerToken();
        if (hasText(bearerToken)) {
            builder.header("Authorization", "Bearer " + bearerToken);
        } else if (hasText(properties.getApiKey())) {
            builder.header("X-Api-Key", properties.getApiKey());
        }
        return builder;
    }

    private HttpRequest.Builder proxyRequest(String baseUrl, String path, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json");
        String bearerToken = bearerToken();
        if (hasText(bearerToken)) {
            builder.header("Authorization", "Bearer " + bearerToken);
        } else if (hasText(properties.getApiKey())) {
            builder.header("X-Api-Key", properties.getApiKey());
        }
        return builder;
    }

    private String bearerToken() {
        if (hasText(properties.getBearerToken())) {
            return properties.getBearerToken();
        }
        if (!hasText(properties.getJwtSecret()) || !hasText(properties.getJwtTenantId())) {
            return "";
        }
        return mintDevJwt();
    }

    private String mintDevJwt() {
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String header = encoder.encodeToString(json(Map.of(
                    "alg", "HS256",
                    "typ", "JWT")).getBytes(StandardCharsets.UTF_8));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", properties.getJwtTenantId());
            claims.put("tier", properties.getJwtTier());
            claims.put("query_limit", properties.getJwtQueryLimit());
            claims.put("exp", clock.instant().plus(properties.getJwtTtl()).getEpochSecond());
            claims.put("iss", properties.getJwtIssuer());
            claims.put("aud", properties.getJwtAudience());
            String payload = encoder.encodeToString(json(claims).getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = encoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to mint FlyVM dev JWT: " + ex.getMessage(), ex);
        }
    }

    private SandboxCommandResult parseCommandResult(
            String sessionId,
            String body,
            Instant startedAt,
            Instant completedAt,
            SandboxCommand command,
            String shellCommand,
            String baseUrl) {
        try {
            JsonNode node = objectMapper.readTree(body);
            int exitCode = intText(node, "exitCode", intText(node, "exit_code", intText(node, "rc", intText(node, "code", 0))));
            String stdout = text(node, "stdout", text(node, "out", ""));
            String stderr = text(node, "stderr", text(node, "err", ""));
            String commandId = text(node, "commandId", text(node, "command_id", UUID.randomUUID().toString()));
            boolean ok = !node.has("ok") || node.get("ok").asBoolean(exitCode == 0);
            Optional<SandboxFailure> failure = exitCode == 0 && ok
                    ? Optional.empty()
                    : Optional.of(new SandboxFailure(
                            FirecrackerSandboxProvider.PROVIDER_ID,
                            sessionId,
                            SandboxFailureCode.COMMAND_ERROR,
                            stderr.isBlank() ? "FlyVM guest command failed" : stderr,
                            "terminal.execute",
                            true,
                            Map.of("cmd", shellCommand)));
            return new SandboxCommandResult(
                    FirecrackerSandboxProvider.PROVIDER_ID,
                    sessionId,
                    commandId,
                    exitCode,
                    stdout,
                    stderr,
                    startedAt,
                    completedAt,
                    failure,
                    commandMetadata(command, shellCommand, baseUrl));
        } catch (Exception ex) {
            SandboxFailure failure = new SandboxFailure(
                    FirecrackerSandboxProvider.PROVIDER_ID,
                    sessionId,
                    SandboxFailureCode.COMMAND_ERROR,
                    "FlyVM guest exec returned invalid JSON: " + ex.getMessage(),
                    "terminal.execute.parse",
                    true,
                    Map.of("body", body, "cmd", shellCommand));
            return new SandboxCommandResult(
                    FirecrackerSandboxProvider.PROVIDER_ID,
                    sessionId,
                    UUID.randomUUID().toString(),
                    -1,
                    "",
                    body,
                    startedAt,
                    completedAt,
                    Optional.of(failure),
                    commandMetadata(command, shellCommand, baseUrl));
        }
    }

    private Map<String, String> commandMetadata(SandboxCommand command, String shellCommand, String baseUrl) {
        Map<String, String> metadata = new LinkedHashMap<>(command.metadata());
        metadata.put("flyvm.agentBaseUrl", baseUrl);
        metadata.put("flyvm.shellCommand", shellCommand);
        return Map.copyOf(metadata);
    }

    private String shellCommand(SandboxCommand command) {
        Map<String, String> env = command.environment() == null ? Map.of() : command.environment();
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            parts.add(entry.getKey() + "=" + shellQuote(entry.getValue()));
        }
        String commandText = command.command();
        if (command.arguments() != null && !command.arguments().isEmpty()) {
            StringBuilder builder = new StringBuilder(commandText);
            for (String argument : command.arguments()) {
                builder.append(' ').append(shellQuote(argument));
            }
            commandText = builder.toString();
        }
        parts.add(commandText);
        return String.join(" ", parts);
    }

    private long timeoutMillis(Duration timeout) {
        Duration effective = timeout == null || timeout.isZero() || timeout.isNegative()
                ? properties.getCommandTimeout()
                : timeout;
        return effective.toMillis();
    }

    private HttpResponse<String> sendString(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private FirecrackerSandboxException restFailure(
            String sessionId,
            String operation,
            String message,
            HttpResponse<String> response) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("httpStatus", Integer.toString(response.statusCode()));
        metadata.put("body", response.body());
        return new FirecrackerSandboxException(new SandboxFailure(
                FirecrackerSandboxProvider.PROVIDER_ID,
                sessionId,
                response.statusCode() == 429 ? SandboxFailureCode.QUOTA_EXCEEDED : SandboxFailureCode.PROVIDER_UNAVAILABLE,
                message + ": HTTP " + response.statusCode() + " " + restErrorMessage(response.body()),
                operation,
                true,
                metadata));
    }

    private String restErrorMessage(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String code = text(error, "code", "");
            String message = text(error, "message", "");
            return firstNonBlank((code + " " + message).trim(), body);
        } catch (Exception ex) {
            log.debug("restErrorMessage fallback: {}", ex.toString());
            return body;
        }
    }

    private String requirePublicEndpoint(String sessionId, String field, String endpoint, String requirement) {
        String value = firstNonBlank(endpoint, "");
        if (!hasText(value)) {
            throw endpointFailure(sessionId, field, "FlyVM REST response is missing " + field + ". " + requirement, value);
        }
        String host = uriHost(value);
        if (!hasText(host)) {
            throw endpointFailure(sessionId, field, "FlyVM REST response has invalid " + field + ": " + value, value);
        }
        if (isPrivateEndpointHost(host)) {
            throw endpointFailure(
                    sessionId,
                    field,
                    "FlyVM REST response returned private endpoint " + value + ". " + requirement,
                    value);
        }
        return value;
    }

    private String validateOptionalEndpoint(String sessionId, String field, String endpoint) {
        String value = firstNonBlank(endpoint, "");
        if (!hasText(value)) {
            return "";
        }
        String host = uriHost(value);
        if (!hasText(host)) {
            throw endpointFailure(sessionId, field, "FlyVM REST response has invalid " + field + ": " + value, value);
        }
        return isPrivateEndpointHost(host) ? "" : value;
    }

    private FirecrackerSandboxException endpointFailure(String sessionId, String field, String message, String endpoint) {
        return new FirecrackerSandboxException(new SandboxFailure(
                FirecrackerSandboxProvider.PROVIDER_ID,
                sessionId,
                SandboxFailureCode.PROVIDER_UNAVAILABLE,
                message,
                "flyvm.endpoints",
                true,
                Map.of("field", field, "endpoint", endpoint == null ? "" : endpoint)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static void putIfHasText(Map<String, Object> values, String key, String value) {
        if (hasText(value)) {
            values.put(key, value);
        }
    }

    private static String nestedText(JsonNode node, String objectKey, String valueKey, String fallback) {
        JsonNode child = node == null ? null : node.get(objectKey);
        return text(child, valueKey, fallback);
    }

    private static String uriHost(String value) {
        if (!hasText(value)) {
            return "";
        }
        try {
            String normalized = value.contains("://") ? value : "vnc://" + value;
            return firstNonBlank(URI.create(normalized).getHost(), "");
        } catch (Exception ex) {
            log.debug("uriHost fallback: {}", ex.toString());
            return "";
        }
    }

    private static boolean isPrivateEndpointHost(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.equals("localhost")
                || normalized.equals("0.0.0.0")
                || normalized.equals("::1")
                || normalized.equals("::")
                || normalized.startsWith("127.")
                || normalized.startsWith("169.254.")) {
            return true;
        }
        String[] octets = normalized.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 10
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException ex) {
            log.debug("isPrivateEndpointHost fallback: {}", ex.toString());
            return false;
        }
    }

    private static String normalizeVncUrl(String value) {
        if (!hasText(value) || value.contains("://")) {
            return firstNonBlank(value, "");
        }
        return "vnc://" + value;
    }

    private String absoluteApiUrl(String value) {
        if (!hasText(value) || value.contains("://")) {
            return firstNonBlank(value, "");
        }
        if (value.startsWith("/")) {
            return properties.getApiBaseUrl() + value;
        }
        return properties.getApiBaseUrl() + "/" + value;
    }

    private static int intValue(Map<String, String> metadata, String key, int fallback) {
        try {
            String value = metadata.get(key);
            return hasText(value) ? Integer.parseInt(value) : fallback;
        } catch (RuntimeException ex) {
            log.debug("intValue fallback: {}", ex.toString());
            return fallback;
        }
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static int intText(JsonNode node, String key, int fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? fallback : value.asInt(fallback);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    record ProvisionedComputer(
            String tenantId,
            String vmId,
            String hostId,
            String state,
            String serviceType,
            String vsockPath,
            String apiAddr,
            String agentApiAddr,
            String noVncUrl,
            String vncUrl,
            String proxyVncUrl,
            String guestHost) {
    }

    record SessionEndpoints(
            String agentBaseUrl,
            String noVncUrl,
            String vncUrl,
            int noVncPort,
            int vncPort,
            int agentPort) {
    }
}
