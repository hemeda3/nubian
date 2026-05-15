package com.nubian.ai.runtime.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Registry of named {@link McpClient} instances managed by this application.
 *
 * <p>Clients are registered by name. Registration triggers the MCP initialization
 * handshake (blocking, 30-second timeout). At application shutdown, all registered
 * clients are closed gracefully.
 *
 * <p>Thread-safe.
 */
@Component
public class McpServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistry.class);

    private static final long INIT_TIMEOUT_SECONDS = 30;

    private final ConcurrentHashMap<String, McpClient> clients = new ConcurrentHashMap<>();

    /**
     * Initializes and registers a named {@link McpClient}.
     *
     * <p>The client's {@link McpClient#initialize()} future is joined with a 30-second
     * timeout. If initialization fails or times out, the client is closed before the
     * exception is rethrown.
     *
     * @param name   unique server name; must not be null or blank
     * @param client a configured but not-yet-initialized client
     * @return the registered client (same instance as passed in)
     * @throws IllegalArgumentException if {@code name} is null or blank
     * @throws IllegalStateException    if a client with {@code name} is already registered
     * @throws RuntimeException         if initialization fails or times out
     */
    public McpClient register(String name, McpClient client) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Server name must not be null or blank");
        }
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (clients.containsKey(name)) {
            throw new IllegalStateException(
                    "An MCP client named '" + name + "' is already registered.");
        }

        try {
            client.initialize()
                    .orTimeout(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
        } catch (Exception ex) {
            // Close client before rethrowing so resources are not leaked
            try {
                client.close();
            } catch (Exception closeEx) {
                log.warn("Failed to close client '{}' after init failure: {}", name, closeEx.getMessage());
            }
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof TimeoutException) {
                throw new RuntimeException(
                        "MCP client '" + name + "' initialization timed out after "
                        + INIT_TIMEOUT_SECONDS + " seconds", cause);
            }
            throw new RuntimeException(
                    "MCP client '" + name + "' initialization failed: " + cause.getMessage(), cause);
        }

        McpClient existing = clients.putIfAbsent(name, client);
        if (existing != null) {
            // Race — another thread registered the same name between our check and put
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("Failed to close redundant client '{}': {}", name, ex.getMessage());
            }
            throw new IllegalStateException(
                    "An MCP client named '" + name + "' was registered concurrently.");
        }

        log.info("Registered MCP server '{}' ({})",
                name, client.initializeResult().serverInfo().name());
        return client;
    }

    /**
     * Returns the registered client for {@code name}, or {@link Optional#empty()} if
     * no client with that name exists.
     */
    public Optional<McpClient> get(String name) {
        return Optional.ofNullable(clients.get(name));
    }

    /**
     * Closes and removes the client registered under {@code name}.
     * No-op if no client with that name is registered.
     *
     * @param name the server name to unregister
     */
    public void unregister(String name) {
        McpClient client = clients.remove(name);
        if (client != null) {
            try {
                client.close();
                log.info("Unregistered and closed MCP server '{}'", name);
            } catch (Exception ex) {
                log.warn("Error closing MCP client '{}' during unregister: {}", name, ex.getMessage(), ex);
            }
        }
    }

    /**
     * Returns an immutable snapshot of all currently registered server names.
     */
    public Set<String> registeredServers() {
        return Set.copyOf(clients.keySet());
    }

    /**
     * Closes all registered clients gracefully at application shutdown.
     * Each client is closed independently; an error in one does not prevent others
     * from being closed.
     */
    @PreDestroy
    public void shutdownAll() {
        log.info("Shutting down {} registered MCP client(s)...", clients.size());
        clients.forEach((name, client) -> {
            try {
                client.close();
                log.debug("Closed MCP client '{}'", name);
            } catch (Exception ex) {
                log.warn("Error closing MCP client '{}' during shutdown: {}", name, ex.getMessage(), ex);
            }
        });
        clients.clear();
    }
}
