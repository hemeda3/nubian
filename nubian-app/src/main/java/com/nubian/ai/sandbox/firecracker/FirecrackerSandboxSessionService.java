package com.nubian.ai.sandbox.firecracker;

import com.nubian.ai.sandbox.api.SandboxSessionService;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import com.nubian.ai.sandbox.model.SandboxSession;
import com.nubian.ai.sandbox.model.SandboxSessionStatus;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirecrackerSandboxSessionService implements SandboxSessionService {
    private static final Logger log = LoggerFactory.getLogger(FirecrackerSandboxSessionService.class);
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final String providerId;
    private final FirecrackerSandboxProperties properties;
    private final FlyVmComputerClient flyVmClient;
    private final ConcurrentMap<String, SandboxSession> sessions = new ConcurrentHashMap<>();

    public FirecrackerSandboxSessionService() {
        this(
                FirecrackerSandboxProvider.PROVIDER_ID,
                new FirecrackerSandboxProperties(),
                new FlyVmComputerClient(
                        new FirecrackerSandboxProperties(),
                        new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public FirecrackerSandboxSessionService(String providerId) {
        this(
                providerId,
                new FirecrackerSandboxProperties(),
                new FlyVmComputerClient(
                        new FirecrackerSandboxProperties(),
                        new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public FirecrackerSandboxSessionService(
            FirecrackerSandboxProperties properties,
            FlyVmComputerClient flyVmClient) {
        this(FirecrackerSandboxProvider.PROVIDER_ID, properties, flyVmClient);
    }

    public FirecrackerSandboxSessionService(
            String providerId,
            FirecrackerSandboxProperties properties,
            FlyVmComputerClient flyVmClient) {
        if (FirecrackerSandboxFailures.isBlank(providerId)) {
            throw new IllegalArgumentException("providerId is required");
        }
        this.providerId = providerId;
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
        this.flyVmClient = java.util.Objects.requireNonNull(flyVmClient, "flyVmClient");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "implementation", "flyvm",
                "runtime", "firecracker",
                "microvmLifecycle", "flyvm-scheduler",
                "guestApi", "flyvm-agent-api");
    }

    @Override
    public CompletableFuture<SandboxSession> createSession(
            Map<String, String> labels,
            Map<String, String> metadata) {

        Map<String, String> safeLabels = copy(labels);
        Map<String, String> safeMetadata = copy(metadata);
        String sessionId = requestedSessionId(safeMetadata).orElseGet(() -> "fc-" + UUID.randomUUID());
        SandboxFailure validation = validateSessionId(sessionId, "sessions.createSession");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }

        if (sessions.containsKey(sessionId)) {
            return FirecrackerSandboxFailures.failedFuture(FirecrackerSandboxFailures.conflict(
                    providerId,
                    sessionId,
                    "sessions.createSession",
                    "Firecracker sandbox session already exists"));
        }

        return CompletableFuture.supplyAsync(() -> provisionRunningSession(
                sessionId,
                safeLabels,
                safeMetadata,
                "sessions.createSession"));
    }

    @Override
    public CompletableFuture<Optional<SandboxSession>> getSession(String sessionId) {
        if (FirecrackerSandboxFailures.isBlank(sessionId)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(Optional.ofNullable(sessions.get(sessionId)));
    }

    @Override
    public CompletableFuture<List<SandboxSession>> listSessions(Map<String, String> labels) {
        Map<String, String> safeLabels = copy(labels);
        List<SandboxSession> matching = sessions.values().stream()
                .filter(session -> matchesLabels(session, safeLabels))
                .sorted(Comparator.comparing(SandboxSession::createdAt))
                .toList();
        return CompletableFuture.completedFuture(matching);
    }

    @Override
    public CompletableFuture<SandboxSession> startSession(String sessionId) {
        SandboxFailure validation = validateKnownSession(sessionId, "sessions.startSession");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }

        SandboxSession existing = sessions.get(sessionId);
        if (existing.status() == SandboxSessionStatus.RUNNING) {
            return CompletableFuture.completedFuture(existing);
        }
        return CompletableFuture.supplyAsync(() -> provisionRunningSession(
                sessionId,
                existing.labels(),
                existing.metadata(),
                "sessions.startSession"));
    }

    @Override
    public CompletableFuture<SandboxSession> stopSession(String sessionId) {
        SandboxFailure validation = validateKnownSession(sessionId, "sessions.stopSession");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }

        return CompletableFuture.supplyAsync(() -> {
            SandboxSession existing = sessions.get(sessionId);
            if (existing.status() == SandboxSessionStatus.STOPPED) {
                return existing;
            }
            String vmId = existing.metadata().getOrDefault("flyvm.vmId", "");
            flyVmClient.suspend(sessionId, vmId);
            SandboxSession stopped = update(existing, SandboxSessionStatus.STOPPED, Map.of(
                    "flyvm.lastLifecycleOperation", "suspend"));
            sessions.put(sessionId, stopped);
            return stopped;
        });
    }

    @Override
    public CompletableFuture<Void> deleteSession(String sessionId) {
        SandboxFailure validation = validateKnownSession(sessionId, "sessions.deleteSession");
        if (validation != null) {
            return FirecrackerSandboxFailures.failedFuture(validation);
        }

        return CompletableFuture.runAsync(() -> {
            SandboxSession existing = sessions.remove(sessionId);
            if (existing != null) {
                flyVmClient.destroy(sessionId, existing.metadata().getOrDefault("flyvm.vmId", ""));
            }
        });
    }

    Optional<SandboxSession> findSession(String sessionId) {
        if (FirecrackerSandboxFailures.isBlank(sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    SandboxFailure validateKnownSession(String sessionId, String operation) {
        SandboxFailure validation = validateSessionId(sessionId, operation);
        if (validation != null) {
            return validation;
        }
        if (!sessions.containsKey(sessionId)) {
            return FirecrackerSandboxFailures.notFound(
                    providerId,
                    sessionId,
                    operation,
                    "Firecracker sandbox session was not found");
        }
        return null;
    }

    Optional<SandboxSession> requireRunningSession(String sessionId, String operation) {
        SandboxFailure validation = validateKnownSession(sessionId, operation);
        if (validation != null) {
            throw new FirecrackerSandboxException(validation);
        }
        SandboxSession session = sessions.get(sessionId);
        if (session.status() != SandboxSessionStatus.RUNNING) {
            throw new FirecrackerSandboxException(new SandboxFailure(
                    providerId,
                    sessionId,
                    SandboxFailureCode.SESSION_ERROR,
                    "Firecracker sandbox session is not running",
                    operation,
                    true,
                    Map.of("status", session.status().name())));
        }
        return Optional.of(session);
    }

    FlyVmComputerClient flyVmClient() {
        return flyVmClient;
    }

    /**
     * Re-discover the currently running FlyVM and rebind this session's cached
     * {@code flyvm.agentBaseUrl} / {@code flyvm.vmId} to it. Used to recover
     * transparently from {@code COLLECTION_NOT_FOUND} when the watchdog
     * reprovisions the underlying VM with a fresh id.
     *
     * @return the rebinded baseUrl when the session metadata was updated;
     *         {@code null} when no running computer is currently registered
     *         with FlyVM (caller should treat as "session truly expired").
     */
    public String rediscoverAndRebind(String sessionId) {
        SandboxSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        List<com.fasterxml.jackson.databind.JsonNode> computers;
        try {
            computers = flyVmClient.listComputers();
        } catch (RuntimeException ex) {
            return null;
        }
        if (computers == null || computers.isEmpty()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode running = computers.stream()
                .filter(n -> "running".equalsIgnoreCase(textNode(n, "state")))
                .findFirst()
                .orElse(computers.get(0));
        String runningId = textNode(running, "vm_id");
        if (FirecrackerSandboxFailures.isBlank(runningId)) {
            runningId = textNode(running, "vmId");
        }
        if (FirecrackerSandboxFailures.isBlank(runningId)) {
            return null;
        }
        String previousId = properties.getStaticVmId();
        if (!runningId.equals(previousId)) {
            properties.setStaticVmId(runningId);
        }
        String newBaseUrl = properties.getApiBaseUrl() + "/v1/computers/" + runningId;

        Map<String, String> rebound = new LinkedHashMap<>(session.metadata());
        rebound.put("flyvm.vmId", runningId);
        rebound.put("flyvm.agentBaseUrl", newBaseUrl);
        rebound.put("flyvm.lastRediscoveryAt", Instant.now().toString());
        if (previousId != null && !previousId.equals(runningId)) {
            rebound.put("flyvm.previousVmId", previousId);
        }
        SandboxSession updated = new SandboxSession(
                providerId,
                sessionId,
                session.status(),
                session.createdAt(),
                Instant.now(),
                session.labels(),
                Map.copyOf(rebound),
                session.failure());
        sessions.put(sessionId, updated);
        return newBaseUrl;
    }

    private static String textNode(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        com.fasterxml.jackson.databind.JsonNode value = node.path(field);
        return value.isTextual() ? value.asText("") : "";
    }

    private SandboxSession provisionRunningSession(
            String sessionId,
            Map<String, String> labels,
            Map<String, String> metadata,
            String operation) {
        try {
            if (!FirecrackerSandboxFailures.isBlank(properties.getStaticVmId())) {
                Instant now = Instant.now();
                SandboxSession previous = sessions.get(sessionId);
                Map<String, String> staticMetadata = staticComputerMetadata(metadata, operation);
                SandboxSession session = new SandboxSession(
                        providerId,
                        sessionId,
                        SandboxSessionStatus.RUNNING,
                        previous == null ? now : previous.createdAt(),
                        now,
                        labels,
                        staticMetadata,
                        Optional.empty());
                sessions.put(sessionId, session);
                return session;
            }
            FlyVmComputerClient.ProvisionedComputer computer = flyVmClient.provision(sessionId, metadata);
            FlyVmComputerClient.SessionEndpoints endpoints = flyVmClient.openEndpoints(sessionId, computer);
            flyVmClient.waitForHealth(sessionId, endpoints.agentBaseUrl());
            if (properties.isRepairNoVncProxy()) {
                try {
                    flyVmClient.ensureNoVncProxy(sessionId, endpoints.agentBaseUrl());
                } catch (RuntimeException ex) {
                    log.warn("ensureNoVncProxy fallback: {}", ex.toString());
                    // noVNC repair is best-effort. The session is still valid when the
                    // guest API is healthy; the UI can retry display readiness separately.
                }
            }
            Instant now = Instant.now();
            SandboxSession previous = sessions.get(sessionId);
            SandboxSession session = new SandboxSession(
                    providerId,
                    sessionId,
                    SandboxSessionStatus.RUNNING,
                    previous == null ? now : previous.createdAt(),
                    now,
                    labels,
                    enrichMetadata(metadata, computer, endpoints, operation),
                    Optional.empty());
            sessions.put(sessionId, session);
            return session;
        } catch (FirecrackerSandboxException ex) {
            SandboxSession previous = sessions.get(sessionId);
            if (previous != null) {
                sessions.put(sessionId, new SandboxSession(
                        providerId,
                        sessionId,
                        SandboxSessionStatus.FAILED,
                        previous.createdAt(),
                        Instant.now(),
                        previous.labels(),
                        previous.metadata(),
                        Optional.of(ex.failure())));
            }
            throw ex;
        }
    }

    private SandboxSession update(SandboxSession session, SandboxSessionStatus status, Map<String, String> metadata) {
        Map<String, String> merged = new LinkedHashMap<>(session.metadata());
        merged.putAll(metadata);
        return new SandboxSession(
                providerId,
                session.sessionId(),
                status,
                session.createdAt(),
                Instant.now(),
                session.labels(),
                merged,
                Optional.empty());
    }

    private SandboxFailure validateSessionId(String sessionId, String operation) {
        if (FirecrackerSandboxFailures.isBlank(sessionId)) {
            return FirecrackerSandboxFailures.validation(
                    providerId,
                    "",
                    operation,
                    "Session id is required");
        }
        if (!SAFE_SESSION_ID.matcher(sessionId).matches() || ".".equals(sessionId) || "..".equals(sessionId)) {
            return FirecrackerSandboxFailures.validation(
                    providerId,
                    sessionId,
                    operation,
                    "Session id may only contain letters, numbers, dots, dashes, and underscores");
        }
        return null;
    }

    private Optional<String> requestedSessionId(Map<String, String> metadata) {
        return firstNonBlank(
                metadata.get("sessionId"),
                metadata.get("sandbox.sessionId"),
                metadata.get("firecracker.sessionId"));
    }

    private Optional<String> firstNonBlank(String... values) {
        for (String value : values) {
            if (!FirecrackerSandboxFailures.isBlank(value)) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private boolean matchesLabels(SandboxSession session, Map<String, String> requestedLabels) {
        return requestedLabels.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(session.labels().get(entry.getKey())));
    }

    private Map<String, String> enrichMetadata(
            Map<String, String> metadata,
            FlyVmComputerClient.ProvisionedComputer computer,
            FlyVmComputerClient.SessionEndpoints endpoints,
            String operation) {

        Map<String, String> enriched = new LinkedHashMap<>(copy(metadata));
        enriched.put("firecracker.implementation", "flyvm");
        enriched.put("firecracker.microvmCreated", "true");
        enriched.put("flyvm.lifecycleApi", "rest");
        enriched.put("flyvm.endpointMode", "flyvm-rest-proxy");
        putIfHasText(enriched, "flyvm.tenantId", computer.tenantId());
        enriched.put("flyvm.vmId", computer.vmId());
        enriched.put("flyvm.hostId", computer.hostId());
        putIfHasText(enriched, "flyvm.state", computer.state());
        putIfHasText(enriched, "flyvm.serviceTypeResponse", computer.serviceType());
        enriched.put("flyvm.vsockPath", computer.vsockPath());
        enriched.put("flyvm.apiAddr", computer.apiAddr());
        putIfHasText(enriched, "flyvm.agentApiAddr", computer.agentApiAddr());
        enriched.put("flyvm.guestHost", computer.guestHost());
        enriched.put("flyvm.agentBaseUrl", endpoints.agentBaseUrl());
        enriched.put("flyvm.noVncUrl", endpoints.noVncUrl());
        enriched.put("flyvm.vncUrl", endpoints.vncUrl());
        enriched.put("flyvm.noVncPort", Integer.toString(endpoints.noVncPort()));
        enriched.put("flyvm.vncPort", Integer.toString(endpoints.vncPort()));
        enriched.put("flyvm.agentPort", Integer.toString(endpoints.agentPort()));
        enriched.put("flyvm.lastLifecycleOperation", operation);
        enriched.putIfAbsent("flyvm.memMib", Integer.toString(properties.getMemoryMib()));
        enriched.putIfAbsent("flyvm.vcpu", Integer.toString(properties.getVcpu()));
        enriched.putIfAbsent("flyvm.dataDiskMib", Integer.toString(properties.getDataDiskMib()));
        enriched.putIfAbsent("flyvm.serviceType", properties.getServiceType());
        return Map.copyOf(enriched);
    }

    private Map<String, String> staticComputerMetadata(Map<String, String> metadata, String operation) {
        Map<String, String> enriched = new LinkedHashMap<>(copy(metadata));
        String vmId = properties.getStaticVmId();
        String proxyBase = properties.getApiBaseUrl() + "/v1/computers/" + vmId;
        enriched.put("firecracker.implementation", "flyvm");
        enriched.put("firecracker.microvmCreated", "false");
        enriched.put("flyvm.lifecycleApi", "static-direct");
        enriched.put("flyvm.endpointMode",
                FirecrackerSandboxFailures.isBlank(properties.getDirectNoVncUrl())
                        ? "flyvm-rest-static"
                        : "direct-vnc-static");
        enriched.put("flyvm.vmId", vmId);
        enriched.put("flyvm.hostId", "nasah-prod");
        enriched.put("flyvm.state", "running");
        enriched.put("flyvm.serviceTypeResponse", properties.getServiceType());
        enriched.put("flyvm.agentBaseUrl", proxyBase);
        String noVncUrl = FirecrackerSandboxFailures.isBlank(properties.getDirectNoVncUrl())
                ? proxyBase + "/vnc"
                : properties.getDirectNoVncUrl();
        enriched.put("flyvm.noVncUrl", noVncUrl);
        enriched.put("flyvm.vncUrl", noVncUrl);
        enriched.put("flyvm.noVncPort", Integer.toString(properties.getGuestNoVncPort()));
        enriched.put("flyvm.vncPort", Integer.toString(properties.getGuestVncPort()));
        enriched.put("flyvm.agentPort", Integer.toString(properties.getGuestAgentPort()));
        enriched.put("flyvm.lastLifecycleOperation", operation);
        enriched.putIfAbsent("flyvm.memMib", Integer.toString(properties.getMemoryMib()));
        enriched.putIfAbsent("flyvm.vcpu", Integer.toString(properties.getVcpu()));
        enriched.putIfAbsent("flyvm.dataDiskMib", Integer.toString(properties.getDataDiskMib()));
        enriched.putIfAbsent("flyvm.serviceType", properties.getServiceType());
        return Map.copyOf(enriched);
    }

    private Map<String, String> copy(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    private static void putIfHasText(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }
}
