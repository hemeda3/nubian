package com.nubian.ai.sandbox.firecracker;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Validates the configured FlyVM static VM ID on startup and auto-discovers
 * the first running VM when the configured ID is stale or blank.
 *
 * <p>Call {@link #validateOrDiscover(String)} from a {@code @PostConstruct}
 * or {@code ApplicationReadyEvent} listener. The method is idempotent.
 */
class FlyVmDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(FlyVmDiscoveryService.class);

    private final FlyVmComputerClient client;

    FlyVmDiscoveryService(FlyVmComputerClient client) {
        this.client = client;
    }

    /**
     * Returns the effective VM ID to use:
     * <ul>
     *   <li>If {@code configuredVmId} is non-blank and GET /v1/computers/{id} returns 200,
     *       returns {@code configuredVmId} unchanged.</li>
     *   <li>If {@code configuredVmId} is blank or GET /v1/computers/{id} returns 404,
     *       calls GET /v1/computers and picks the first item whose {@code state} is
     *       {@code "running"} (case-insensitive). Falls back to the first item in the list
     *       if no running VM is found.</li>
     *   <li>If the list is empty, throws {@link IllegalStateException} with an actionable
     *       message telling the operator what to do.</li>
     *   <li>If GET /v1/computers/{id} returns any other non-2xx/non-404 error, logs a WARN
     *       and returns {@code configuredVmId} defensively (the agent loop will surface the
     *       real error on first use).</li>
     * </ul>
     *
     * @param configuredVmId raw value from {@code NUBIAN_FLYVM_STATIC_VM_ID} (may be blank)
     * @return effective VM ID, never blank
     */
    String validateOrDiscover(String configuredVmId) {
        boolean hasConfigured = configuredVmId != null && !configuredVmId.isBlank();

        if (hasConfigured) {
            // Validate the configured ID against the live API
            try {
                String body = client.getComputer(configuredVmId);
                if (body != null) {
                    // 200 — configured ID is valid
                    log.info("[FlyVM] configured VM ID '{}' is valid (confirmed via GET /v1/computers/{})",
                            configuredVmId, configuredVmId);
                    return configuredVmId;
                }
                // 404 — configured ID is stale, fall through to discovery
                log.warn("[FlyVM] configured VM ID '{}' returned 404 (COLLECTION_NOT_FOUND). "
                        + "The VM may have been recreated. Attempting auto-discovery via GET /v1/computers. "
                        + "Update NUBIAN_FLYVM_STATIC_VM_ID once the correct ID is found.",
                        configuredVmId);
            } catch (Exception ex) {
                // Non-404 error — be defensive: return the configured ID and let the
                // agent surface the real error on first use rather than blocking boot.
                log.warn("[FlyVM] Could not validate configured VM ID '{}': {}. "
                        + "Keeping the configured value — the agent loop will report the error on first use.",
                        configuredVmId, ex.getMessage());
                return configuredVmId;
            }
        } else {
            log.info("[FlyVM] NUBIAN_FLYVM_STATIC_VM_ID is blank. Attempting auto-discovery via GET /v1/computers.");
        }

        // Discovery: GET /v1/computers
        return discover(configuredVmId);
    }

    private String discover(String previousVmId) {
        List<JsonNode> computers;
        try {
            computers = client.listComputers();
        } catch (Exception ex) {
            String hint = previousVmId != null && !previousVmId.isBlank()
                    ? " (previous VM ID was '" + previousVmId + "')"
                    : "";
            throw new IllegalStateException(
                    "FlyVM auto-discovery failed: could not list computers" + hint
                            + ". Check FlyVM credentials (FLYVM_JWT_SECRET / FLYVM_TOKEN / FLYVM_API_KEY) "
                            + "and network connectivity to the FlyVM API. Cause: " + ex.getMessage(),
                    ex);
        }

        if (computers.isEmpty()) {
            throw new IllegalStateException(
                    "No FlyVM computer found. Either provision one "
                            + "(visit https://flyvm.io/dashboard) and set NUBIAN_FLYVM_STATIC_VM_ID, "
                            + "or check FlyVM credentials. "
                            + "See nubian-app logs for the JWT exchange URL.");
        }

        // Prefer a running VM; fall back to first item if none is explicitly running
        JsonNode chosen = computers.stream()
                .filter(n -> "running".equalsIgnoreCase(textField(n, "state")))
                .findFirst()
                .orElse(computers.get(0));

        String discoveredId = textField(chosen, "vm_id");
        if (discoveredId == null || discoveredId.isBlank()) {
            discoveredId = textField(chosen, "vmId");
        }
        if (discoveredId == null || discoveredId.isBlank()) {
            throw new IllegalStateException(
                    "FlyVM auto-discovery found " + computers.size()
                            + " computer(s) but none had a vm_id field. "
                            + "Raw first entry: " + chosen);
        }

        String source = previousVmId != null && !previousVmId.isBlank()
                ? "replacing stale configured ID '" + previousVmId + "'"
                : "no ID configured";
        log.info("[FlyVM] Auto-discovered VM ID '{}' ({}, state='{}'). "
                + "Set NUBIAN_FLYVM_STATIC_VM_ID={} to skip discovery on future restarts.",
                discoveredId, source, textField(chosen, "state"), discoveredId);

        return discoveredId;
    }

    private static String textField(JsonNode node, String key) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? "" : v.asText("");
    }
}
