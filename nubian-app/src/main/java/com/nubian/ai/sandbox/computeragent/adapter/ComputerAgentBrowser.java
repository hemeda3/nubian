package com.nubian.ai.sandbox.computeragent.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nubian.ai.sandbox.api.SandboxBrowser;
import com.nubian.ai.sandbox.computeragent.ComputerAgentClient;
import com.nubian.ai.sandbox.computeragent.ComputerAgentException;
import com.nubian.ai.sandbox.model.SandboxBrowserAction;
import com.nubian.ai.sandbox.model.SandboxBrowserObservation;
import com.nubian.ai.sandbox.model.SandboxCapabilityType;
import com.nubian.ai.sandbox.model.SandboxFailure;
import com.nubian.ai.sandbox.model.SandboxFailureCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@link SandboxBrowser} adapter that proxies to a Ubuntu-desktop guest agent
 * via {@link ComputerAgentClient}.
 *
 * <p>CDP-based browser operations are routed through POST /browser/cdp/command.
 * pyautogui-based pointer/keyboard operations use POST /hands/pyautogui.
 *
 * <p>No Spring, no Lombok — pure POJO. Bean wiring is Stream D's responsibility.
 */
public class ComputerAgentBrowser implements SandboxBrowser {

    private static final Logger log = LoggerFactory.getLogger(ComputerAgentBrowser.class);

    static final String PROVIDER_ID_DEFAULT = "computer-agent";

    private final String providerId;
    private final ComputerAgentClient client;
    private final ObjectMapper mapper;

    public ComputerAgentBrowser(
            String providerId,
            ComputerAgentClient client,
            ObjectMapper mapper) {
        this.providerId = providerId == null ? PROVIDER_ID_DEFAULT : providerId;
        this.client = client;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------------
    // SandboxCapability
    // -------------------------------------------------------------------------

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public SandboxCapabilityType type() {
        return SandboxCapabilityType.BROWSER;
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("runtime", "computer-agent", "protocol", "cdp");
    }

    // -------------------------------------------------------------------------
    // SandboxBrowser — performAction
    // -------------------------------------------------------------------------

    /**
     * Dispatches the action to the appropriate guest-agent endpoint based on
     * {@link SandboxBrowserAction.Type}.
     */
    @Override
    public CompletableFuture<SandboxBrowserObservation> performAction(
            String sessionId, SandboxBrowserAction action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return dispatch(sessionId, action);
            } catch (ComputerAgentException e) {
                throw new ComputerAgentRuntimeException(
                        toFailure(sessionId, "browser.performAction." + action.type().name().toLowerCase(), e));
            }
        });
    }

    // -------------------------------------------------------------------------
    // SandboxBrowser — observe
    // -------------------------------------------------------------------------

    /**
     * Observes the current browser state: takes a CDP screenshot and reads the
     * current URL and title via Runtime.evaluate.
     */
    @Override
    public CompletableFuture<SandboxBrowserObservation> observe(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return observeInternal(sessionId);
            } catch (ComputerAgentException e) {
                throw new ComputerAgentRuntimeException(
                        toFailure(sessionId, "browser.observe", e));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    private SandboxBrowserObservation dispatch(
            String sessionId, SandboxBrowserAction action) {
        return switch (action.type()) {
            case NAVIGATE -> navigate(sessionId, action);
            case CLICK -> click(sessionId, action);
            case TYPE -> type(sessionId, action);
            case PRESS_KEY -> pressKey(sessionId, action);
            case SCROLL -> scroll(sessionId, action);
            case BACK -> back(sessionId);
            case FORWARD -> forward(sessionId);
            case RELOAD -> reload(sessionId);
            case WAIT -> wait(sessionId, action);
            case EVALUATE -> evaluate(sessionId, action);
            case SCREENSHOT -> screenshot(sessionId);
        };
    }

    // -------------------------------------------------------------------------
    // CDP-based actions
    // -------------------------------------------------------------------------

    private SandboxBrowserObservation navigate(
            String sessionId, SandboxBrowserAction action) {
        String url = action.parameters().getOrDefault("url", "");
        ObjectNode params = mapper.createObjectNode();
        params.put("url", url);
        JsonNode result = client.cdpCommand("Page.navigate", params);
        return observationFromCdpResult(sessionId, result, "navigate");
    }

    private SandboxBrowserObservation back(String sessionId) {
        JsonNode result = client.cdpCommand("Page.goBack", null);
        return observationFromCdpResult(sessionId, result, "back");
    }

    private SandboxBrowserObservation forward(String sessionId) {
        JsonNode result = client.cdpCommand("Page.goForward", null);
        return observationFromCdpResult(sessionId, result, "forward");
    }

    private SandboxBrowserObservation reload(String sessionId) {
        JsonNode result = client.cdpCommand("Page.reload", null);
        return observationFromCdpResult(sessionId, result, "reload");
    }

    private SandboxBrowserObservation evaluate(
            String sessionId, SandboxBrowserAction action) {
        String expression = action.parameters().getOrDefault("expression", "");
        ObjectNode params = mapper.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        params.put("awaitPromise", true);
        JsonNode result = client.cdpCommand("Runtime.evaluate", params);
        return observationFromCdpResult(sessionId, result, "evaluate");
    }

    private SandboxBrowserObservation screenshot(String sessionId) {
        // /eyes/screenshot is reliable across guests (OSWorld, FlyVM-Ubuntu,
        // Docker-Ubuntu); CDP Page.captureScreenshot resets the connection on
        // OSWorld today. Prefer the universal route.
        byte[] screenshotBytes = client.screenshot();
        return new SandboxBrowserObservation(
                providerId, sessionId, "", "", "",
                "image/png", screenshotBytes == null ? new byte[0] : screenshotBytes,
                Instant.now(), Map.of("display.source", "eyes/screenshot"));
    }

    // -------------------------------------------------------------------------
    // pyautogui-based actions
    // -------------------------------------------------------------------------

    private SandboxBrowserObservation click(
            String sessionId, SandboxBrowserAction action) {
        int x = parseIntParam(action, "x");
        int y = parseIntParam(action, "y");
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "click");
        body.put("x", x);
        body.put("y", y);
        JsonNode result = client.handsAction(body);
        return observationFromActionResult(sessionId, result, "click");
    }

    private SandboxBrowserObservation type(
            String sessionId, SandboxBrowserAction action) {
        String text = action.parameters().getOrDefault("text", "");
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "type");
        body.put("text", text);
        JsonNode result = client.handsAction(body);
        return observationFromActionResult(sessionId, result, "type");
    }

    private SandboxBrowserObservation pressKey(
            String sessionId, SandboxBrowserAction action) {
        String key = action.parameters().getOrDefault("key", "");
        // Split on "+" to support combos like "ctrl+c"
        String[] keys = key.split("\\+");
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "hotkey");
        var keysArray = body.putArray("keys");
        for (String k : keys) {
            keysArray.add(k.trim());
        }
        JsonNode result = client.handsAction(body);
        return observationFromActionResult(sessionId, result, "press_key");
    }

    private SandboxBrowserObservation scroll(
            String sessionId, SandboxBrowserAction action) {
        // direction: "up" | "down" | "left" | "right"; amount defaults to 3
        String direction = action.parameters().getOrDefault("direction", "down");
        int amount = parseIntParamWithDefault(action, "amount", 3);
        int scrollAmount = "up".equalsIgnoreCase(direction) || "left".equalsIgnoreCase(direction)
                ? -amount : amount;
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "scroll");
        body.put("amount", scrollAmount);
        JsonNode result = client.handsAction(body);
        return observationFromActionResult(sessionId, result, "scroll");
    }

    private SandboxBrowserObservation wait(
            String sessionId, SandboxBrowserAction action) {
        int ms = parseIntParamWithDefault(action, "ms", 1000);
        double seconds = ms / 1000.0;
        ObjectNode body = mapper.createObjectNode();
        body.put("type", "wait");
        body.put("seconds", seconds);
        JsonNode result = client.handsAction(body);
        return observationFromActionResult(sessionId, result, "wait");
    }

    // -------------------------------------------------------------------------
    // Observe
    // -------------------------------------------------------------------------

    private SandboxBrowserObservation observeInternal(String sessionId) {
        // Prefer the universal /eyes/screenshot endpoint over CDP — OSWorld's
        // CDP transport resets connections, and the eyes route works on every
        // Ubuntu desktop guest. Falls back to empty bytes if the call fails.
        byte[] screenshotBytes;
        try {
            screenshotBytes = client.screenshot();
            if (screenshotBytes == null) screenshotBytes = new byte[0];
        } catch (RuntimeException ex) {
            log.warn("observeInternal: /eyes/screenshot failed for session {} — returning empty bytes: {}",
                    sessionId, ex.toString());
            screenshotBytes = new byte[0];
        }

        // Read current URL + title
        ObjectNode urlExpr = mapper.createObjectNode();
        urlExpr.put("expression", "window.location.href");
        urlExpr.put("returnByValue", true);
        urlExpr.put("awaitPromise", false);
        JsonNode urlResult = client.cdpCommand("Runtime.evaluate", urlExpr);

        ObjectNode titleExpr = mapper.createObjectNode();
        titleExpr.put("expression", "document.title");
        titleExpr.put("returnByValue", true);
        titleExpr.put("awaitPromise", false);
        JsonNode titleResult = client.cdpCommand("Runtime.evaluate", titleExpr);

        String url = extractStringValue(urlResult);
        String title = extractStringValue(titleResult);

        return new SandboxBrowserObservation(
                providerId, sessionId, url, title, "",
                "image/png", screenshotBytes, Instant.now(), Map.of());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SandboxBrowserObservation observationFromCdpResult(
            String sessionId, JsonNode result, String op) {
        return new SandboxBrowserObservation(
                providerId, sessionId, "", "", "",
                "image/png", new byte[0], Instant.now(),
                Map.of("operation", op));
    }

    private SandboxBrowserObservation observationFromActionResult(
            String sessionId, JsonNode result, String op) {
        return new SandboxBrowserObservation(
                providerId, sessionId, "", "", "",
                "image/png", new byte[0], Instant.now(),
                Map.of("operation", op));
    }

    private byte[] extractScreenshotBytes(JsonNode result) {
        if (result == null) {
            return new byte[0];
        }
        // CDP Page.captureScreenshot returns {"result":{"data":"<base64>"}}
        JsonNode dataNode = result.path("result").path("data");
        if (dataNode.isMissingNode() || dataNode.isNull()) {
            // Try top-level "data" field
            dataNode = result.path("data");
        }
        if (!dataNode.isMissingNode() && !dataNode.isNull()) {
            try {
                return Base64.getDecoder().decode(dataNode.asText());
            } catch (IllegalArgumentException ex) {
                log.warn("extractScreenshotBytes: base64 decode failed — returning empty bytes: {}",
                        ex.toString());
            }
        }
        return new byte[0];
    }

    private String extractStringValue(JsonNode result) {
        if (result == null) {
            return "";
        }
        // CDP Runtime.evaluate returns {"result":{"result":{"value":"..."}}}
        JsonNode valueNode = result.path("result").path("result").path("value");
        if (!valueNode.isMissingNode() && !valueNode.isNull()) {
            return valueNode.asText("");
        }
        // Simpler shape {"result":{"value":"..."}}
        valueNode = result.path("result").path("value");
        if (!valueNode.isMissingNode() && !valueNode.isNull()) {
            return valueNode.asText("");
        }
        return "";
    }

    private static int parseIntParam(SandboxBrowserAction action, String key) {
        String val = action.parameters().get(key);
        if (val == null || val.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseIntParamWithDefault(SandboxBrowserAction action, String key, int defaultValue) {
        String val = action.parameters().get(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private SandboxFailure toFailure(String sessionId, String operation, ComputerAgentException e) {
        return SandboxFailure.of(providerId, sessionId, SandboxFailureCode.BROWSER_ERROR,
                e.getMessage(), operation);
    }

    // -------------------------------------------------------------------------
    // Internal unchecked wrapper to escape CompletableFuture's lambda
    // -------------------------------------------------------------------------

    static final class ComputerAgentRuntimeException extends RuntimeException {
        private final SandboxFailure failure;

        ComputerAgentRuntimeException(SandboxFailure failure) {
            super(failure.message());
            this.failure = failure;
        }

        SandboxFailure failure() {
            return failure;
        }
    }
}
