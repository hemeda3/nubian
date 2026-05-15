package com.nubian.ai.runtime.mcp;

import com.nubian.ai.runtime.mcp.auth.McpTokenStore;
import com.nubian.ai.runtime.mcp.client.ElicitationProvider;
import com.nubian.ai.runtime.mcp.client.RootsProvider;
import com.nubian.ai.runtime.mcp.client.SamplingProvider;
import com.nubian.ai.runtime.mcp.lifecycle.ClientCapabilities;
import com.nubian.ai.runtime.mcp.lifecycle.ClientInfo;
import com.nubian.ai.runtime.mcp.transport.McpTransport;
import com.nubian.ai.runtime.mcp.transport.StdioTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;

/**
 * Fluent builder for constructing {@link McpClient} instances.
 *
 * <p>Exactly one of {@link #stdio(ProcessBuilder)} or {@link #http(URI)} must be
 * configured before calling {@link #build()}. Configuring both or neither throws
 * {@link IllegalStateException}.
 *
 * <p>Example — stdio:
 * <pre>{@code
 * McpClient client = McpClientBuilder.builder()
 *     .stdio(new ProcessBuilder("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
 *     .build();
 * }</pre>
 *
 * <p>Example — HTTP:
 * <pre>{@code
 * McpClient client = McpClientBuilder.builder()
 *     .http(URI.create("https://my-mcp-server/mcp"))
 *     .withTokenStore(myTokenStore)
 *     .build();
 * }</pre>
 */
public final class McpClientBuilder {

    private static final Logger log = LoggerFactory.getLogger(McpClientBuilder.class);

    // Transport selection
    private ProcessBuilder stdioProcessBuilder;
    private URI httpEndpoint;

    // Configuration
    private McpClientConfig config = McpClientConfig.defaults();

    // Providers (optional)
    private RootsProvider rootsProvider;
    private SamplingProvider samplingProvider;
    private ElicitationProvider elicitationProvider;

    private McpClientBuilder() {}

    /** Returns a new builder instance. */
    public static McpClientBuilder builder() {
        return new McpClientBuilder();
    }

    /**
     * Configures this client to connect to an MCP server via stdio.
     *
     * @param pb the configured {@link ProcessBuilder} for the MCP server process
     * @return this builder
     */
    public McpClientBuilder stdio(ProcessBuilder pb) {
        this.stdioProcessBuilder = pb;
        return this;
    }

    /**
     * Configures this client to connect to an MCP server via Streamable HTTP.
     *
     * @param endpoint the MCP endpoint URI (e.g. {@code https://host/mcp})
     * @return this builder
     */
    public McpClientBuilder http(URI endpoint) {
        this.httpEndpoint = endpoint;
        return this;
    }

    /**
     * Replaces the entire config with the supplied value.
     *
     * @param config the config to use; must not be null
     * @return this builder
     */
    public McpClientBuilder withConfig(McpClientConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
        return this;
    }

    /**
     * Overrides only the {@link ClientCapabilities} in the current config.
     *
     * @param capabilities the capabilities to advertise; must not be null
     * @return this builder
     */
    public McpClientBuilder withCapabilities(ClientCapabilities capabilities) {
        if (capabilities == null) throw new IllegalArgumentException("capabilities must not be null");
        this.config = new McpClientConfig(
                config.connectTimeout(),
                config.requestTimeout(),
                config.heartbeatCadence(),
                config.autoStartHeartbeat(),
                capabilities,
                config.clientInfo(),
                config.tokenStore());
        return this;
    }

    /**
     * Overrides only the {@link ClientInfo} in the current config.
     *
     * @param clientInfo the client metadata to send; must not be null
     * @return this builder
     */
    public McpClientBuilder withClientInfo(ClientInfo clientInfo) {
        if (clientInfo == null) throw new IllegalArgumentException("clientInfo must not be null");
        this.config = new McpClientConfig(
                config.connectTimeout(),
                config.requestTimeout(),
                config.heartbeatCadence(),
                config.autoStartHeartbeat(),
                config.capabilities(),
                clientInfo,
                config.tokenStore());
        return this;
    }

    /**
     * Overrides only the {@link McpTokenStore} in the current config.
     *
     * @param tokenStore the token store; must not be null
     * @return this builder
     */
    public McpClientBuilder withTokenStore(McpTokenStore tokenStore) {
        if (tokenStore == null) throw new IllegalArgumentException("tokenStore must not be null");
        this.config = new McpClientConfig(
                config.connectTimeout(),
                config.requestTimeout(),
                config.heartbeatCadence(),
                config.autoStartHeartbeat(),
                config.capabilities(),
                config.clientInfo(),
                tokenStore);
        return this;
    }

    /**
     * Sets the {@link RootsProvider} to register after initialization.
     *
     * @param rootsProvider the provider; {@code null} clears any previously set value
     * @return this builder
     */
    public McpClientBuilder withRootsProvider(RootsProvider rootsProvider) {
        this.rootsProvider = rootsProvider;
        return this;
    }

    /**
     * Sets the {@link SamplingProvider} to register after initialization.
     *
     * @param samplingProvider the provider; {@code null} clears any previously set value
     * @return this builder
     */
    public McpClientBuilder withSamplingProvider(SamplingProvider samplingProvider) {
        this.samplingProvider = samplingProvider;
        return this;
    }

    /**
     * Sets the {@link ElicitationProvider} to register after initialization.
     *
     * @param elicitationProvider the provider; {@code null} clears any previously set value
     * @return this builder
     */
    public McpClientBuilder withElicitationProvider(ElicitationProvider elicitationProvider) {
        this.elicitationProvider = elicitationProvider;
        return this;
    }

    /**
     * Builds the {@link McpClient}.
     *
     * @return a configured but not-yet-initialized {@link McpClient}
     * @throws IllegalStateException if neither or both transport types are configured
     * @throws IllegalStateException if the stdio process cannot be started
     */
    public McpClient build() {
        if (stdioProcessBuilder != null && httpEndpoint != null) {
            throw new IllegalStateException(
                    "Specify either stdio() or http(), not both.");
        }
        if (stdioProcessBuilder == null && httpEndpoint == null) {
            throw new IllegalStateException(
                    "A transport must be configured. Call stdio() or http() before build().");
        }

        McpTransport transport = buildTransport();
        McpClient client = new McpClient(transport, config);

        if (rootsProvider != null) {
            client.setRootsProvider(rootsProvider);
        }
        if (samplingProvider != null) {
            client.setSamplingProvider(samplingProvider);
        }
        if (elicitationProvider != null) {
            client.setElicitationProvider(elicitationProvider);
        }

        return client;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private McpTransport buildTransport() {
        if (stdioProcessBuilder != null) {
            try {
                StdioTransport t = new StdioTransport(stdioProcessBuilder);
                t.startReadLoop();
                return t;
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "Failed to start stdio MCP server process: " + ex.getMessage(), ex);
            }
        }

        // HTTP transport — loaded reflectively so this module compiles even if
        // StreamableHttpTransport is built by a parallel agent.
        return buildHttpTransport();
    }

    /**
     * Constructs a {@code StreamableHttpTransport} via reflection to avoid a hard
     * compile-time dependency on the class (built by a parallel agent slice).
     *
     * <p>Expected constructor signature:
     * <pre>
     *   StreamableHttpTransport(URI mcpEndpoint,
     *                           java.util.function.Supplier&lt;java.util.Optional&lt;String&gt;&gt; bearerSupplier,
     *                           TransportConfig config)
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private McpTransport buildHttpTransport() {
        try {
            Class<?> clazz = Class.forName(
                    "com.nubian.ai.runtime.mcp.transport.StreamableHttpTransport");

            // Build a bearer supplier from the token store using the endpoint as the resource key
            final String resource = httpEndpoint.toString();
            final McpTokenStore store = config.tokenStore();
            java.util.function.Supplier<java.util.Optional<String>> bearerSupplier = () ->
                    store.get(resource).map(token -> token.accessToken());

            // Load TransportConfig class for the third constructor arg
            Class<?> transportConfigClass = Class.forName(
                    "com.nubian.ai.runtime.mcp.transport.TransportConfig");

            // Use the no-arg factory / default instance if available, otherwise pass null
            Object transportConfig = tryGetDefaultTransportConfig(transportConfigClass);

            java.lang.reflect.Constructor<?> ctor = clazz.getConstructor(
                    URI.class,
                    java.util.function.Supplier.class,
                    transportConfigClass);

            McpTransport transport = (McpTransport) ctor.newInstance(
                    httpEndpoint, bearerSupplier, transportConfig);

            // Start the read loop if available
            try {
                java.lang.reflect.Method startReadLoop = clazz.getMethod("startReadLoop");
                startReadLoop.invoke(transport);
            } catch (NoSuchMethodException ex) {
                log.debug("buildStreamableHttpTransport no startReadLoop method: {}", ex.toString());
                // Method may not exist on all implementations
            }

            return transport;
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(
                    "StreamableHttpTransport is not on the classpath. "
                    + "The HTTP transport slice may not have been built yet.", ex);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to construct StreamableHttpTransport for endpoint "
                    + httpEndpoint + ": " + ex.getMessage(), ex);
        }
    }

    private static Object tryGetDefaultTransportConfig(Class<?> transportConfigClass) {
        // Try a static defaults() or instance() method, then no-arg constructor
        for (String methodName : new String[]{"defaults", "instance", "getDefault"}) {
            try {
                java.lang.reflect.Method m = transportConfigClass.getMethod(methodName);
                return m.invoke(null);
            } catch (Exception ex) {
                log.debug("tryGetDefaultTransportConfig method {} not available: {}", methodName, ex.toString());
                // try next
            }
        }
        try {
            return transportConfigClass.getDeclaredConstructor().newInstance();
        } catch (Exception ex) {
            log.debug("tryGetDefaultTransportConfig no-arg constructor failed: {}", ex.toString());
            return null;
        }
    }
}
