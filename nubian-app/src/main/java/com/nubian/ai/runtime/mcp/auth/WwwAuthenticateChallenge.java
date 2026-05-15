package com.nubian.ai.runtime.mcp.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed representation of a {@code WWW-Authenticate: Bearer} challenge header.
 *
 * <p>RFC 6750 bearer token scheme with MCP-specific extensions:
 * <ul>
 *   <li>{@code realm} — optional human-readable realm name</li>
 *   <li>{@code error} — {@code invalid_token}, {@code insufficient_scope}, etc.</li>
 *   <li>{@code error_description} — human-readable error detail</li>
 *   <li>{@code scope} — space-delimited required scopes</li>
 *   <li>{@code resource_metadata} — URL of the protected resource metadata document
 *       (RFC 9728 extension used by MCP)</li>
 * </ul>
 *
 * <p>Use {@link #parse(String)} to create an instance from a raw header value.
 */
public record WwwAuthenticateChallenge(
        String scheme,
        String realm,
        String error,
        String errorDescription,
        String scope,
        String resourceMetadata) {

    // Matches: key="value" or key=value (unquoted token)
    private static final Pattern PARAM = Pattern.compile(
            "([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s\"]+))");

    /**
     * Parses a raw {@code WWW-Authenticate} header value into a structured record.
     *
     * <p>If the header is blank or does not begin with {@code Bearer} (case-insensitive),
     * returns a minimal record with scheme set to {@code "Bearer"} and all other fields
     * {@code null}.
     *
     * @param header the raw header value (e.g. {@code Bearer realm="example", error="..."})
     * @return a parsed {@link WwwAuthenticateChallenge}
     */
    public static WwwAuthenticateChallenge parse(String header) {
        if (header == null || header.isBlank()) {
            return new WwwAuthenticateChallenge("Bearer", null, null, null, null, null);
        }

        String trimmed = header.trim();
        // Extract scheme (first token)
        int spaceIdx = trimmed.indexOf(' ');
        String scheme = spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
        String paramSection = spaceIdx > 0 ? trimmed.substring(spaceIdx + 1) : "";

        String realm = null;
        String error = null;
        String errorDescription = null;
        String scope = null;
        String resourceMetadata = null;

        Matcher m = PARAM.matcher(paramSection);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            // group(2) is the quoted value; group(3) is the unquoted token
            String value = m.group(2) != null ? m.group(2) : m.group(3);
            switch (key) {
                case "realm" -> realm = value;
                case "error" -> error = value;
                case "error_description" -> errorDescription = value;
                case "scope" -> scope = value;
                case "resource_metadata" -> resourceMetadata = value;
                default -> { /* ignore unknown params for forward-compat */ }
            }
        }

        return new WwwAuthenticateChallenge(scheme, realm, error, errorDescription, scope, resourceMetadata);
    }
}
