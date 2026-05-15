package com.nubian.ai.runtime.mcp;

import com.nubian.ai.runtime.mcp.client.ElicitationHandler;
import com.nubian.ai.runtime.mcp.client.ElicitationProvider;
import com.nubian.ai.runtime.mcp.client.RootsHandler;
import com.nubian.ai.runtime.mcp.client.RootsProvider;
import com.nubian.ai.runtime.mcp.client.SamplingHandler;
import com.nubian.ai.runtime.mcp.client.SamplingProvider;
import com.nubian.ai.runtime.mcp.lifecycle.InitializeResult;
import com.nubian.ai.runtime.mcp.lifecycle.LifecycleManager;
import com.nubian.ai.runtime.mcp.prompts.McpCompletionsClient;
import com.nubian.ai.runtime.mcp.prompts.McpPromptsClient;
import com.nubian.ai.runtime.mcp.resources.McpResourcesClient;
import com.nubian.ai.runtime.mcp.tasks.McpTasksClient;
import com.nubian.ai.runtime.mcp.tools.McpToolsClient;
import com.nubian.ai.runtime.mcp.transport.McpTransport;
import com.nubian.ai.runtime.mcp.util.CancellationManager;
import com.nubian.ai.runtime.mcp.util.McpLoggingClient;
import com.nubian.ai.runtime.mcp.util.PingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Top-level MCP client facade.
 *
 * <p>Owns the transport, lifecycle, and all sub-clients. Typical usage:
 * <pre>{@code
 * McpClient client = McpClientBuilder.builder()
 *     .stdio(new ProcessBuilder("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
 *     .build();
 * client.initialize().join();
 * List<ToolDefinition> tools = client.tools().listTools(null).join().tools();
 * client.close();
 * }</pre>
 *
 * <p>Thread-safety: {@link #initialize()}, {@link #setRootsProvider},
 * {@link #setSamplingProvider}, and {@link #setElicitationProvider} are
 * {@code synchronized}. Sub-client accessors are lazily initialized with
 * double-checked locking.
 */
public final class McpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    // -----------------------------------------------------------------------
    // Core infrastructure
    // -----------------------------------------------------------------------

    private final McpTransport transport;
    private final McpClientConfig config;
    private final LifecycleManager lifecycle = new LifecycleManager();
    private final PingService pingService = new PingService();
    private final CancellationManager cancellationManager = new CancellationManager();

    // -----------------------------------------------------------------------
    // Lazy-initialized sub-clients
    // -----------------------------------------------------------------------

    private volatile McpToolsClient tools;
    private volatile McpResourcesClient resources;
    private volatile McpPromptsClient prompts;
    private volatile McpTasksClient tasks;
    private volatile McpLoggingClient logging;
    private volatile McpCompletionsClient completions;

    // -----------------------------------------------------------------------
    // Optional providers
    // -----------------------------------------------------------------------

    private Optional<RootsProvider> rootsProvider = Optional.empty();
    private Optional<SamplingProvider> samplingProvider = Optional.empty();
    private Optional<ElicitationProvider> elicitationProvider = Optional.empty();

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final AtomicReference<InitializeResult> initResult = new AtomicReference<>();
    private volatile AutoCloseable heartbeatHandle;

    /**
     * Constructs an {@link McpClient} with the given transport and configuration.
     *
     * @param transport the MCP transport (stdio, HTTP, etc.)
     * @param config    client configuration
     */
    public McpClient(McpTransport transport, McpClientConfig config) {
        if (transport == null) throw new IllegalArgumentException("transport must not be null");
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.transport = transport;
        this.config = config;
    }

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    /**
     * Performs the MCP initialization handshake asynchronously.
     *
     * <p>On success:
     * <ol>
     *   <li>Stores the server's {@link InitializeResult}.</li>
     *   <li>Registers {@link RootsHandler}, {@link SamplingHandler}, and
     *       {@link ElicitationHandler} if providers are present.</li>
     *   <li>Opens the server-initiated stream if the transport supports it
     *       (detected via reflection — {@code openServerStream()}).</li>
     *   <li>Starts the ping heartbeat if {@link McpClientConfig#autoStartHeartbeat()}
     *       is {@code true}.</li>
     * </ol>
     *
     * @return future resolving to the server's {@link InitializeResult}
     */
    public synchronized CompletableFuture<InitializeResult> initialize() {
        return lifecycle.initialize(transport, config.capabilities(), config.clientInfo())
                .thenApply(result -> {
                    initResult.set(result);
                    registerHandlers();
                    tryOpenServerStream();
                    if (config.autoStartHeartbeat()) {
                        heartbeatHandle = startHeartbeat(config.heartbeatCadence());
                    }
                    log.info("MCP client initialized: server={} protocolVersion={}",
                            result.serverInfo().name(), result.protocolVersion());
                    return result;
                });
    }

    // -----------------------------------------------------------------------
    // Sub-client accessors (lazy, double-checked)
    // -----------------------------------------------------------------------

    /** Returns (lazily initializing) the tools sub-client. */
    public McpToolsClient tools() {
        if (tools == null) {
            synchronized (this) {
                if (tools == null) {
                    tools = new McpToolsClient();
                }
            }
        }
        return tools;
    }

    /** Returns (lazily initializing) the resources sub-client. */
    public McpResourcesClient resources() {
        if (resources == null) {
            synchronized (this) {
                if (resources == null) {
                    resources = new McpResourcesClient();
                }
            }
        }
        return resources;
    }

    /** Returns (lazily initializing) the prompts sub-client. */
    public McpPromptsClient prompts() {
        if (prompts == null) {
            synchronized (this) {
                if (prompts == null) {
                    prompts = new McpPromptsClient();
                }
            }
        }
        return prompts;
    }

    /** Returns (lazily initializing) the tasks sub-client. */
    public McpTasksClient tasks() {
        if (tasks == null) {
            synchronized (this) {
                if (tasks == null) {
                    tasks = new McpTasksClient();
                }
            }
        }
        return tasks;
    }

    /** Returns (lazily initializing) the logging sub-client. */
    public McpLoggingClient logging() {
        if (logging == null) {
            synchronized (this) {
                if (logging == null) {
                    logging = new McpLoggingClient();
                }
            }
        }
        return logging;
    }

    /** Returns (lazily initializing) the completions sub-client. */
    public McpCompletionsClient completions() {
        if (completions == null) {
            synchronized (this) {
                if (completions == null) {
                    completions = new McpCompletionsClient();
                }
            }
        }
        return completions;
    }

    /** Returns the underlying transport. */
    public McpTransport transport() {
        return transport;
    }

    /**
     * Returns the cached {@link InitializeResult}.
     *
     * @throws IllegalStateException if {@link #initialize()} has not completed successfully
     */
    public InitializeResult initializeResult() {
        InitializeResult result = initResult.get();
        if (result == null) {
            throw new IllegalStateException(
                    "McpClient has not been initialized. Call initialize() first.");
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Provider setters
    // -----------------------------------------------------------------------

    /**
     * Sets (or replaces) the {@link RootsProvider}.
     * If already initialized, immediately registers the handler on the transport.
     */
    public synchronized void setRootsProvider(RootsProvider provider) {
        this.rootsProvider = Optional.ofNullable(provider);
        if (initResult.get() != null && provider != null) {
            RootsHandler.register(transport, provider);
        }
    }

    /**
     * Sets (or replaces) the {@link SamplingProvider}.
     * If already initialized, immediately registers the handler on the transport.
     */
    public synchronized void setSamplingProvider(SamplingProvider provider) {
        this.samplingProvider = Optional.ofNullable(provider);
        if (initResult.get() != null && provider != null) {
            SamplingHandler.register(transport, provider);
        }
    }

    /**
     * Sets (or replaces) the {@link ElicitationProvider}.
     * If already initialized, immediately registers the handler on the transport.
     */
    public synchronized void setElicitationProvider(ElicitationProvider provider) {
        this.elicitationProvider = Optional.ofNullable(provider);
        if (initResult.get() != null && provider != null) {
            ElicitationHandler.register(transport, provider);
        }
    }

    // -----------------------------------------------------------------------
    // Heartbeat
    // -----------------------------------------------------------------------

    /**
     * Starts a periodic ping heartbeat at the given cadence.
     *
     * @param cadence interval between pings
     * @return an {@link AutoCloseable} that stops the heartbeat when closed
     */
    public AutoCloseable startHeartbeat(Duration cadence) {
        return pingService.startHeartbeat(
                transport,
                cadence,
                failure -> log.warn("MCP heartbeat failure: consecutive={} error={}",
                        failure.consecutiveFailures(), failure.lastError().getMessage())
        );
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Closes this client: stops the heartbeat, signals the cancellation manager,
     * shuts down the lifecycle, and closes the transport.
     */
    @Override
    public void close() {
        // Stop heartbeat
        if (heartbeatHandle != null) {
            try {
                heartbeatHandle.close();
            } catch (Exception ex) {
                log.warn("Error stopping heartbeat: {}", ex.getMessage(), ex);
            }
        }

        // Signal pending cancellations (best-effort: no in-flight requests at this point)
        // CancellationManager has no explicit shutdown method; it is GC'd with this instance.

        // Shutdown lifecycle + transport
        try {
            lifecycle.shutdown(transport);
        } catch (Exception ex) {
            log.warn("Error during lifecycle shutdown: {}", ex.getMessage(), ex);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void registerHandlers() {
        rootsProvider.ifPresent(p -> RootsHandler.register(transport, p));
        samplingProvider.ifPresent(p -> SamplingHandler.register(transport, p));
        elicitationProvider.ifPresent(p -> ElicitationHandler.register(transport, p));
    }

    /**
     * If the transport exposes an {@code openServerStream()} method (i.e. it is a
     * {@link com.nubian.ai.runtime.mcp.transport.AbstractMcpTransport} subclass that
     * implements SSE), invoke it via reflection so this class does not have a hard
     * compile-time dependency on {@code StreamableHttpTransport}.
     */
    private void tryOpenServerStream() {
        try {
            java.lang.reflect.Method m = transport.getClass().getMethod("openServerStream");
            m.invoke(transport);
            log.debug("openServerStream() invoked on {}", transport.getClass().getSimpleName());
        } catch (NoSuchMethodException ex) {
            log.debug("tryOpenServerStream no openServerStream method on {}: {}", transport.getClass().getSimpleName(), ex.toString());
            // Not an HTTP transport, or transport doesn't expose this method — normal for stdio.
        } catch (Exception ex) {
            log.warn("openServerStream() failed: {}", ex.getMessage(), ex);
        }
    }
}
