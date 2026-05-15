package com.nubian.ai.runtime.mcp.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

/**
 * Parameters for an OAuth 2.1 authorization code request, including PKCE and
 * RFC 8707 resource indicator.
 *
 * <p>Use {@link #buildUrl(String)} to produce the full authorization redirect URI.
 *
 * @param clientId    the OAuth client identifier
 * @param redirectUris the registered redirect URIs; the first one is used
 * @param resource    the RFC 8707 resource indicator (the MCP server URI)
 * @param scopes      requested scopes
 * @param state       opaque CSRF-protection state value
 * @param pkce        the PKCE challenge generated for this request
 */
public record AuthorizationCodeRequest(
        String clientId,
        List<String> redirectUris,
        String resource,
        List<String> scopes,
        String state,
        PkceChallenge pkce) {

    /**
     * Builds the full authorization URL by appending all required query parameters
     * to {@code authorizationEndpoint}.
     *
     * @param authorizationEndpoint the AS authorization endpoint URL
     * @return a {@link URI} to redirect the user-agent to
     */
    public URI buildUrl(String authorizationEndpoint) {
        String redirectUri = redirectUris != null && !redirectUris.isEmpty()
                ? redirectUris.get(0) : "";

        StringBuilder sb = new StringBuilder(authorizationEndpoint);
        sb.append(authorizationEndpoint.contains("?") ? '&' : '?');
        sb.append("response_type=code");
        sb.append("&client_id=").append(encode(clientId));
        sb.append("&redirect_uri=").append(encode(redirectUri));
        if (resource != null && !resource.isBlank()) {
            sb.append("&resource=").append(encode(resource));
        }
        if (scopes != null && !scopes.isEmpty()) {
            StringJoiner sj = new StringJoiner(" ");
            scopes.forEach(sj::add);
            sb.append("&scope=").append(encode(sj.toString()));
        }
        if (state != null && !state.isBlank()) {
            sb.append("&state=").append(encode(state));
        }
        sb.append("&code_challenge=").append(encode(pkce.codeChallenge()));
        sb.append("&code_challenge_method=").append(encode(pkce.method()));

        return URI.create(sb.toString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
