package com.nubian.ai.runtime.mcp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubian.ai.runtime.mcp.protocol.McpJsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * OAuth 2.1 + PKCE flow implementation for MCP HTTP-transport authorization.
 *
 * <p>All token endpoint interactions use {@code application/x-www-form-urlencoded} POST
 * over HTTP/1.1 (matching the {@code LlmClient} transport pattern).
 *
 * <p>Key enforcement points:
 * <ul>
 *   <li>S256 PKCE is always required. If the AS metadata does not list
 *       {@code code_challenge_methods_supported} containing {@code S256},
 *       {@link #buildAuthorizationUrl} throws {@link McpAuthException}.</li>
 *   <li>The RFC 8707 {@code resource} parameter is included in both the
 *       authorization request and the token exchange.</li>
 * </ul>
 */
public class McpOAuthFlow {

    private static final Logger LOG = Logger.getLogger(McpOAuthFlow.class.getName());

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final ObjectMapper mapper;

    /** Creates a flow instance with a fresh HTTP/1.1 client and the shared MCP mapper. */
    public McpOAuthFlow() {
        this(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build(),
            McpJsonMapper.instance());
    }

    /** Package-private constructor for testing. */
    McpOAuthFlow(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Assembles the OAuth 2.1 authorization URL to redirect the user-agent to.
     *
     * <p>Validates that the AS supports S256 before proceeding — refuses to
     * build the URL if {@code code_challenge_methods_supported} is absent or
     * does not contain {@code S256}.
     *
     * @param as       the authorization server metadata
     * @param creds    the client credentials (provides client_id and first redirect URI)
     * @param scopes   requested scopes
     * @param resource the RFC 8707 resource indicator (MCP server URI)
     * @param pkce     the pre-generated PKCE challenge
     * @param state    opaque CSRF protection state value
     * @return the full authorization URL as a {@link URI}
     * @throws McpAuthException if the AS does not advertise S256 PKCE support
     */
    public URI buildAuthorizationUrl(
            AuthorizationServerMetadata as,
            ClientCredentials creds,
            List<String> scopes,
            String resource,
            PkceChallenge pkce,
            String state) {

        requireS256Support(as);

        AuthorizationCodeRequest req = new AuthorizationCodeRequest(
                creds.clientId(),
                creds.redirectUris(),
                resource,
                scopes,
                state,
                pkce);

        return req.buildUrl(as.authorizationEndpoint());
    }

    /**
     * Exchanges an authorization code for an access token.
     *
     * @param as           the authorization server metadata
     * @param creds        the client credentials
     * @param code         the authorization code from the redirect
     * @param redirectUri  the redirect URI used in the authorization request
     * @param resource     the RFC 8707 resource indicator
     * @param codeVerifier the PKCE verifier matching the original challenge
     * @return a {@link CompletableFuture} that resolves to the {@link AccessToken}
     */
    public CompletableFuture<AccessToken> exchangeCodeForToken(
            AuthorizationServerMetadata as,
            ClientCredentials creds,
            String code,
            String redirectUri,
            String resource,
            String codeVerifier) {

        return CompletableFuture.supplyAsync(() -> {
            List<String[]> params = new ArrayList<>();
            params.add(new String[]{"grant_type", "authorization_code"});
            params.add(new String[]{"code", code});
            params.add(new String[]{"redirect_uri", redirectUri});
            params.add(new String[]{"client_id", creds.clientId()});
            params.add(new String[]{"code_verifier", codeVerifier});
            if (resource != null && !resource.isBlank()) {
                params.add(new String[]{"resource", resource});
            }
            // Confidential clients send their secret
            String secret = extractSecret(creds);
            if (secret != null) {
                params.add(new String[]{"client_secret", secret});
            }
            return postTokenEndpoint(as.tokenEndpoint(), params);
        });
    }

    /**
     * Refreshes an access token using a refresh token grant.
     *
     * @param as           the authorization server metadata
     * @param creds        the client credentials
     * @param refreshToken the refresh token
     * @param resource     the RFC 8707 resource indicator
     * @return a {@link CompletableFuture} resolving to the new {@link AccessToken}
     */
    public CompletableFuture<AccessToken> refresh(
            AuthorizationServerMetadata as,
            ClientCredentials creds,
            String refreshToken,
            String resource) {

        return CompletableFuture.supplyAsync(() -> {
            List<String[]> params = new ArrayList<>();
            params.add(new String[]{"grant_type", "refresh_token"});
            params.add(new String[]{"refresh_token", refreshToken});
            params.add(new String[]{"client_id", creds.clientId()});
            if (resource != null && !resource.isBlank()) {
                params.add(new String[]{"resource", resource});
            }
            String secret = extractSecret(creds);
            if (secret != null) {
                params.add(new String[]{"client_secret", secret});
            }
            return postTokenEndpoint(as.tokenEndpoint(), params);
        });
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private void requireS256Support(AuthorizationServerMetadata as) {
        List<String> methods = as.codeChallengeMethodsSupported();
        if (methods == null || methods.isEmpty()) {
            throw new McpAuthException(
                    "Authorization server does not advertise code_challenge_methods_supported. "
                    + "S256 PKCE is required by the MCP 2025-11-25 specification.");
        }
        if (!methods.contains("S256")) {
            throw new McpAuthException(
                    "Authorization server does not support S256 PKCE (supported: " + methods + "). "
                    + "S256 is required; plain is forbidden.");
        }
    }

    private AccessToken postTokenEndpoint(String tokenEndpoint, List<String[]> params) {
        String body = buildFormBody(params);

        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new McpAuthException("Token endpoint request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpAuthException("Token endpoint request interrupted", e);
        }

        if (resp.statusCode() / 100 != 2) {
            throw new McpAuthException(
                    "Token endpoint returned HTTP " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode node;
        try {
            node = mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new McpAuthException("Invalid JSON from token endpoint: " + e.getMessage(), e);
        }

        String accessTokenValue = node.path("access_token").asText(null);
        if (accessTokenValue == null || accessTokenValue.isBlank()) {
            throw new McpAuthException("Token endpoint response missing 'access_token'");
        }

        String tokenType = node.path("token_type").asText("Bearer");
        Long expiresIn = node.has("expires_in") && !node.get("expires_in").isNull()
                ? node.path("expires_in").asLong() : null;
        String refreshToken = node.path("refresh_token").asText(null);
        String scopeStr = node.path("scope").asText(null);
        List<String> scopes = (scopeStr != null && !scopeStr.isBlank())
                ? List.of(scopeStr.split("\\s+")) : null;

        return new AccessToken(accessTokenValue, tokenType, expiresIn, refreshToken, scopes, Instant.now());
    }

    private static String buildFormBody(List<String[]> params) {
        StringJoiner sj = new StringJoiner("&");
        for (String[] kv : params) {
            sj.add(encode(kv[0]) + "=" + encode(kv[1]));
        }
        return sj.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String extractSecret(ClientCredentials creds) {
        if (creds instanceof ClientCredentials.PreRegisteredClient c) {
            return c.clientSecret();
        }
        if (creds instanceof ClientCredentials.DynamicallyRegisteredClient c) {
            return c.clientSecret();
        }
        return null; // ClientIdMetadataDocument — no secret
    }
}
