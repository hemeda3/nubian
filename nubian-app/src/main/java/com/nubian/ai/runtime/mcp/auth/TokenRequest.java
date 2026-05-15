package com.nubian.ai.runtime.mcp.auth;

/**
 * Parameters for an OAuth 2.1 authorization code token exchange request.
 *
 * <p>All fields are sent as {@code application/x-www-form-urlencoded} body
 * parameters to the token endpoint.
 *
 * @param clientId      the OAuth client identifier
 * @param clientSecret  the client secret, or {@code null} for public clients
 * @param code          the authorization code received from the AS
 * @param redirectUri   the redirect URI used in the authorization request
 * @param resource      the RFC 8707 resource indicator
 * @param codeVerifier  the PKCE verifier that corresponds to the original challenge
 */
public record TokenRequest(
        String clientId,
        String clientSecret,
        String code,
        String redirectUri,
        String resource,
        String codeVerifier) {
}
