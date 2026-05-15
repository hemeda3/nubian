package com.nubian.ai.runtime.mcp.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (Proof Key for Code Exchange, RFC 7636) challenge pair.
 *
 * <p>Only S256 is ever generated. Plain is explicitly forbidden per the
 * MCP 2025-11-25 authorization spec.
 *
 * <ul>
 *   <li>{@code codeVerifier} — 43-128 character base64url-encoded random string.</li>
 *   <li>{@code codeChallenge} — BASE64URL(SHA256(ASCII(codeVerifier))), unpadded.</li>
 *   <li>{@code method} — always {@code "S256"}.</li>
 * </ul>
 */
public record PkceChallenge(String codeVerifier, String codeChallenge, String method) {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // 96 raw bytes -> 128 base64url chars (within the 43-128 range)
    private static final int VERIFIER_BYTE_LENGTH = 96;

    /**
     * Generates a fresh PKCE S256 challenge.
     *
     * @return a new {@link PkceChallenge} with a cryptographically random verifier
     * @throws IllegalStateException if SHA-256 is unavailable (should never happen on JDK 8+)
     */
    public static PkceChallenge generate() {
        byte[] randomBytes = new byte[VERIFIER_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);

        String verifier = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        String challenge = computeChallenge(verifier);
        return new PkceChallenge(verifier, challenge, "S256");
    }

    /**
     * Recomputes the S256 challenge from an existing verifier.
     * Useful for validating a stored verifier against a known challenge.
     *
     * @param verifier the code_verifier string
     * @return BASE64URL(SHA256(ASCII(verifier))), unpadded
     */
    public static String computeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
