package com.nubian.ai.runtime.mcp.auth;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Authorization interceptor designed to plug into {@code StreamableHttpTransport} (slice 3).
 *
 * <p>Provides three entry points the transport calls:
 * <ol>
 *   <li>{@link #authorizationHeader} — fast path: if a valid token is already stored,
 *       returns {@code "Bearer <token>"} immediately.</li>
 *   <li>{@link #handle401} — full OAuth 2.1 + PKCE flow triggered by a 401 response:
 *       parse WWW-Authenticate, discover AS, build authorization URL, invoke the
 *       caller-supplied {@code browserOpener} to obtain the authorization code,
 *       exchange for a token, store it, return it.</li>
 *   <li>{@link #handle403InsufficientScope} — step-up authorization triggered by a
 *       403 with {@code insufficient_scope} error: re-run the authorization flow
 *       requesting the additional scopes advertised in the WWW-Authenticate challenge.</li>
 * </ol>
 *
 * <h3>Browser/UX delegation</h3>
 * <p>The constructor takes a {@code Function<URI, CompletableFuture<String>> browserOpener}.
 * The interceptor calls it with the authorization URL and expects back the authorization
 * code string. Callers wire this to a loopback HTTP callback server, a system browser
 * launch + user paste UX, or any other mechanism appropriate for their environment.
 *
 * <h3>Thread safety</h3>
 * <p>All state is held in the injected {@link McpTokenStore} (whose
 * {@link McpTokenStore.InMemoryTokenStore} is thread-safe). The interceptor itself is
 * stateless and safe for concurrent use once constructed.
 */
public class McpAuthInterceptor {

    private static final Logger LOG = Logger.getLogger(McpAuthInterceptor.class.getName());

    private final McpAuthDiscovery discovery;
    private final McpOAuthFlow oauthFlow;
    private final McpTokenStore tokenStore;
    /** Receives the authorization URL; resolves to the authorization code. */
    private final Function<URI, CompletableFuture<String>> browserOpener;

    /**
     * Creates an interceptor with default discovery and flow instances.
     *
     * @param tokenStore    the store to read/write tokens from
     * @param browserOpener a function that opens the authorization URL and returns the
     *                      authorization code asynchronously
     */
    public McpAuthInterceptor(
            McpTokenStore tokenStore,
            Function<URI, CompletableFuture<String>> browserOpener) {
        this(new McpAuthDiscovery(), new McpOAuthFlow(), tokenStore, browserOpener);
    }

    /** Full constructor for testing with custom collaborators. */
    McpAuthInterceptor(
            McpAuthDiscovery discovery,
            McpOAuthFlow oauthFlow,
            McpTokenStore tokenStore,
            Function<URI, CompletableFuture<String>> browserOpener) {
        this.discovery = discovery;
        this.oauthFlow = oauthFlow;
        this.tokenStore = tokenStore;
        this.browserOpener = browserOpener;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns a valid {@code Authorization: Bearer <token>} header value if one exists
     * in the store for {@code resource} and has not expired (including the 30s buffer).
     *
     * @param mcpEndpoint the MCP server endpoint (used for logging context only)
     * @param store       the token store (callers may pass a different store instance)
     * @param resource    the canonical resource URI (RFC 8707)
     * @return {@code Optional.of("Bearer <token>")} if a non-expired token is available,
     *         otherwise {@code Optional.empty()}
     */
    public Optional<String> authorizationHeader(URI mcpEndpoint, McpTokenStore store, String resource) {
        return store.get(resource)
                .filter(t -> !t.isExpired())
                .map(t -> "Bearer " + t.accessToken());
    }

    /**
     * Handles a 401 Unauthorized response by running the full OAuth 2.1 + PKCE flow.
     *
     * <p>Steps:
     * <ol>
     *   <li>Parse the {@code WWW-Authenticate} header from the 401 response.</li>
     *   <li>Discover Protected Resource Metadata (via {@code resource_metadata} hint in
     *       the header, or by probing the MCP endpoint's well-known path).</li>
     *   <li>Fetch Authorization Server Metadata for the first listed AS.</li>
     *   <li>Generate a fresh PKCE challenge.</li>
     *   <li>Build the authorization URL and hand it to {@code browserOpener}.</li>
     *   <li>Exchange the returned code for an access token.</li>
     *   <li>Store the token keyed by the resource URI.</li>
     *   <li>Return the token to the caller.</li>
     * </ol>
     *
     * @param response the 401 HTTP response
     * @param creds    the client credentials to use for the flow
     * @return a {@link CompletableFuture} that resolves to the new {@link AccessToken}
     */
    public CompletableFuture<AccessToken> handle401(
            HttpResponse<?> response,
            ClientCredentials creds) {

        String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse("");
        WwwAuthenticateChallenge challenge = WwwAuthenticateChallenge.parse(wwwAuth);

        URI requestUri = response.request().uri();

        return CompletableFuture.supplyAsync(() -> discoverProtectedResource(challenge, requestUri))
                .thenCompose(prm -> runAuthFlow(prm, creds, null));
    }

    /**
     * Handles a 403 with {@code insufficient_scope} by requesting step-up authorization.
     *
     * <p>Parses the {@code WWW-Authenticate} header on the 403 response for the
     * {@code scope} parameter advertising the required scopes, then re-runs the
     * authorization flow. The existing token is removed from the store before the
     * new flow begins.
     *
     * @param response the 403 HTTP response
     * @param creds    the client credentials
     * @param existing the existing (insufficient) access token, removed from the store
     * @return a {@link CompletableFuture} resolving to the upgraded {@link AccessToken}
     */
    public CompletableFuture<AccessToken> handle403InsufficientScope(
            HttpResponse<?> response,
            ClientCredentials creds,
            AccessToken existing) {

        String wwwAuth = response.headers().firstValue("WWW-Authenticate").orElse("");
        WwwAuthenticateChallenge challenge = WwwAuthenticateChallenge.parse(wwwAuth);

        URI requestUri = response.request().uri();

        // Extract required scopes from the challenge's scope parameter
        List<String> requiredScopes = challenge.scope() != null && !challenge.scope().isBlank()
                ? List.of(challenge.scope().split("\\s+"))
                : List.of();

        return CompletableFuture.supplyAsync(() -> discoverProtectedResource(challenge, requestUri))
                .thenCompose(prm -> {
                    // Remove the old insufficient token before requesting step-up
                    tokenStore.remove(prm.resource());
                    return runAuthFlow(prm, creds, requiredScopes);
                });
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private ProtectedResourceMetadata discoverProtectedResource(
            WwwAuthenticateChallenge challenge, URI requestUri) {
        // If the 401 challenge advertises resource_metadata, fetch it directly
        if (challenge.resourceMetadata() != null && !challenge.resourceMetadata().isBlank()) {
            try {
                return discovery.fetchProtectedResourceMetadata(URI.create(challenge.resourceMetadata()));
            } catch (McpAuthException e) {
                LOG.fine(() -> "resource_metadata hint failed, falling back to endpoint probe: " + e.getMessage());
            }
        }
        // Fall back to well-known probing from the MCP endpoint URI
        return discovery.discoverFromMcpEndpoint(requestUri);
    }

    private CompletableFuture<AccessToken> runAuthFlow(
            ProtectedResourceMetadata prm,
            ClientCredentials creds,
            List<String> overrideScopes) {

        // Pick the first advertised authorization server
        List<String> asUrls = prm.authorizationServers();
        if (asUrls == null || asUrls.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new McpAuthException("No authorization_servers in protected resource metadata for: "
                            + prm.resource()));
        }
        String asUrl = asUrls.get(0);

        AuthorizationServerMetadata asMeta;
        try {
            asMeta = discovery.fetchAuthorizationServerMetadata(asUrl);
        } catch (McpAuthException e) {
            return CompletableFuture.failedFuture(e);
        }

        // Determine scopes: use override (step-up) scopes if provided, else PRM scopes
        List<String> scopes = (overrideScopes != null && !overrideScopes.isEmpty())
                ? overrideScopes
                : prm.scopesSupported();

        String resource = prm.resource();
        PkceChallenge pkce = PkceChallenge.generate();
        String state = UUID.randomUUID().toString();

        URI authUrl;
        try {
            authUrl = oauthFlow.buildAuthorizationUrl(asMeta, creds, scopes, resource, pkce, state);
        } catch (McpAuthException e) {
            return CompletableFuture.failedFuture(e);
        }

        String redirectUri = creds.redirectUris() != null && !creds.redirectUris().isEmpty()
                ? creds.redirectUris().get(0) : "";

        // Delegate to the caller-supplied browser opener; it returns the authorization code
        return browserOpener.apply(authUrl)
                .thenCompose(code -> oauthFlow.exchangeCodeForToken(
                        asMeta, creds, code, redirectUri, resource, pkce.codeVerifier()))
                .thenApply(token -> {
                    tokenStore.put(resource, token);
                    return token;
                });
    }
}
