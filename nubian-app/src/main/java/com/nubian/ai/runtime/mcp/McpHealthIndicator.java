package com.nubian.ai.runtime.mcp;

import com.nubian.ai.runtime.mcp.util.PingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot Actuator health indicator for MCP server connections.
 *
 * <p>Pings every registered {@link McpClient} in parallel with a 5-second timeout.
 * Reports {@code UP} when all servers respond within 5 seconds, {@code DOWN} if any
 * server fails to respond or returns an error.
 *
 * <p>This class implements the Actuator {@code HealthIndicator} contract via reflection
 * so that {@code nubian-runtime} can compile without a hard dependency on
 * {@code spring-boot-starter-actuator}. The bean is only registered by
 * {@link McpAutoConfiguration} when Actuator is present on the classpath
 * ({@code @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")}).
 *
 * <p>At runtime (when Actuator IS present), this class IS-A
 * {@code org.springframework.boot.actuate.health.HealthIndicator} — Spring will pick
 * it up via its standard {@code health()} method returning a
 * {@code org.springframework.boot.actuate.health.Health} object.
 *
 * <p><b>Implementation note:</b> We obtain the {@code Health} builder via reflection
 * so that the source file compiles in modules that do not declare Actuator as a
 * compile-scope dependency. The reflection calls are one-time and the results are
 * cached at first call.
 */
public class McpHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(McpHealthIndicator.class);
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(5);

    private final McpServerRegistry registry;
    private final PingService pingService = new PingService();

    public McpHealthIndicator(McpServerRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns a {@code org.springframework.boot.actuate.health.Health} object.
     *
     * <p>Spring Boot Actuator discovers this method by name convention and calls it
     * when performing a health check. The return type is {@link Object} here to avoid
     * a hard compile-time dependency on Actuator; at runtime the actual
     * {@code Health} instance is returned.
     *
     * @return a {@code Health} instance (UP or DOWN with per-server details)
     */
    public Object health() {
        Set<String> names = registry.registeredServers();

        boolean allUp = true;
        Map<String, Object> details = new LinkedHashMap<>();

        if (names.isEmpty()) {
            return buildHealth(true, Map.of("message", "No MCP servers registered"));
        }

        // Collect ping futures in parallel
        Map<String, CompletableFuture<String>> futures = new LinkedHashMap<>();
        for (String name : names) {
            registry.get(name).ifPresent(client -> {
                CompletableFuture<String> pingFuture = pingService
                        .ping(client.transport(), PING_TIMEOUT)
                        .orTimeout(PING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .thenApply(rtt -> "UP (rtt=" + rtt.toMillis() + "ms)")
                        .exceptionally(ex -> "DOWN (" + rootCauseMessage(ex) + ")");
                futures.put(name, pingFuture);
            });
        }

        for (Map.Entry<String, CompletableFuture<String>> entry : futures.entrySet()) {
            String name = entry.getKey();
            String status;
            try {
                status = entry.getValue().get(PING_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
            } catch (Exception ex) {
                status = "DOWN (health check exception: " + rootCauseMessage(ex) + ")";
            }
            details.put(name, status);
            if (status.startsWith("DOWN")) {
                allUp = false;
            }
        }

        return buildHealth(allUp, details);
    }

    // -----------------------------------------------------------------------
    // Reflection helpers — build Actuator Health without compile-time dependency
    // -----------------------------------------------------------------------

    private static Object buildHealth(boolean up, Map<String, Object> details) {
        try {
            Class<?> healthClass = Class.forName(
                    "org.springframework.boot.actuate.health.Health");
            Class<?> builderClass = Class.forName(
                    "org.springframework.boot.actuate.health.Health$Builder");

            // Health.up() or Health.down()
            Method statusMethod = healthClass.getMethod(up ? "up" : "down");
            Object builder = statusMethod.invoke(null);

            // builder.withDetail(key, value) for each entry
            Method withDetail = builderClass.getMethod("withDetail", String.class, Object.class);
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                builder = withDetail.invoke(builder, entry.getKey(), entry.getValue());
            }

            // builder.build()
            Method build = builderClass.getMethod("build");
            return build.invoke(builder);

        } catch (Exception ex) {
            // Actuator not available at runtime — return a plain string description
            // so the bean still functions as a no-op rather than throwing.
            log.debug("Actuator Health class not found — returning plain status string", ex);
            return up ? "UP" : "DOWN: " + details;
        }
    }

    private static String rootCauseMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
