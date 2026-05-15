package com.nubian.ai.runtime.mcp.client;

import java.util.concurrent.CompletableFuture;

/**
 * Strategy interface for presenting elicitation requests to the user.
 *
 * <p>When a server sends an {@code elicitation/create} request, the
 * {@link ElicitationHandler} delegates to the registered {@link ElicitationProvider}.
 * Implementations may render a form in the client UI, open a browser, or apply
 * automated policy (e.g. auto-decline in headless contexts).
 *
 * <p>The default implementation {@link AutoDeclineElicitationProvider} immediately
 * returns {@link ElicitationAction#DECLINE} without showing anything to the user.
 * Register a real provider via
 * {@link ElicitationHandler#register(com.nubian.ai.runtime.mcp.transport.McpTransport, ElicitationProvider)}
 * to get interactive behaviour.
 */
public interface ElicitationProvider {

    /**
     * Present the elicitation described by {@code params} to the user and return the
     * result.
     *
     * @param params The parameters sent by the server.
     * @return A future that completes with the user's response.
     */
    CompletableFuture<CreateElicitationResult> createElicitation(CreateElicitationParams params);

    /**
     * Default implementation that immediately declines every elicitation request.
     * Suitable for headless / automated contexts where no user interaction is possible.
     */
    class AutoDeclineElicitationProvider implements ElicitationProvider {

        /** Singleton instance. */
        public static final AutoDeclineElicitationProvider INSTANCE =
                new AutoDeclineElicitationProvider();

        private AutoDeclineElicitationProvider() {}

        @Override
        public CompletableFuture<CreateElicitationResult> createElicitation(
                CreateElicitationParams params) {
            return CompletableFuture.completedFuture(
                    CreateElicitationResult.of(ElicitationAction.DECLINE));
        }
    }
}
