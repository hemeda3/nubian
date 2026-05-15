package com.nubian.ai.runtime.mcp.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * An OAuth 2.1 access token response, enriched with the issuance timestamp
 * so expiry can be checked locally without clock synchronization.
 *
 * <p>{@link #isExpired()} returns {@code true} when the token has less than
 * 30 seconds of remaining lifetime (proactive refresh buffer).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessToken(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("scope") List<String> scopes,
        @JsonProperty("issued_at") Instant issuedAt) {

    private static final long EXPIRY_BUFFER_SECONDS = 30L;

    /**
     * Returns {@code true} if the token has expired or will expire within
     * the 30-second buffer window. A token with a {@code null} {@code expiresIn}
     * is considered non-expiring (returns {@code false}).
     */
    public boolean isExpired() {
        if (expiresIn == null || issuedAt == null) {
            return false;
        }
        Instant expiry = issuedAt.plusSeconds(expiresIn).minusSeconds(EXPIRY_BUFFER_SECONDS);
        return Instant.now().isAfter(expiry);
    }
}
