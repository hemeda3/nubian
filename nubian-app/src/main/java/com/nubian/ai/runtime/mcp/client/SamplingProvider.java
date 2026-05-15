package com.nubian.ai.runtime.mcp.client;

import java.util.concurrent.CompletableFuture;

/**
 * Strategy interface for performing LLM completions on behalf of a connected MCP server.
 *
 * <p>When a server sends a {@code sampling/createMessage} request, the
 * {@link SamplingHandler} delegates to the registered {@link SamplingProvider}.
 * Implementations bridge to whichever LLM backend the client has configured.
 *
 * <p>The default implementation {@link NotConfiguredSamplingProvider} rejects all
 * requests with an explanatory {@link IllegalStateException}. Replace it by registering
 * a real provider via {@link SamplingHandler#register(com.nubian.ai.runtime.mcp.transport.McpTransport, SamplingProvider)}.
 */
public interface SamplingProvider {

    /**
     * Perform a sampling (LLM completion) call described by {@code params}.
     *
     * @param params The parameters sent by the server.
     * @return A future that either completes with the model's result or fails
     *         exceptionally (e.g. with {@link IllegalStateException} if the user rejects).
     */
    CompletableFuture<CreateMessageResult> createMessage(CreateMessageParams params);

    // ------------------------------------------------------------------
    // Default implementation — rejects all requests
    // ------------------------------------------------------------------

    /**
     * Stub implementation that rejects every request because no real backend has been
     * configured. Wire in a real {@link SamplingProvider} via
     * {@link SamplingHandler#register} before accepting server connections that declare
     * the {@code sampling} capability.
     */
    final class NotConfiguredSamplingProvider implements SamplingProvider {

        /** Singleton instance. */
        public static final NotConfiguredSamplingProvider INSTANCE =
                new NotConfiguredSamplingProvider();

        private NotConfiguredSamplingProvider() {}

        @Override
        public CompletableFuture<CreateMessageResult> createMessage(CreateMessageParams params) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "SamplingProvider not configured — server requested sampling but client "
                    + "has not registered a backend"));
        }
    }
}
