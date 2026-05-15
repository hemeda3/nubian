package com.nubian.ai.runtime.mcp.auth;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URI canonicalization and validation utilities for OAuth 2.1 / RFC 8707 usage.
 *
 * <p>Rules applied by {@link #normalize(String)}:
 * <ul>
 *   <li>Lowercase scheme and host.</li>
 *   <li>Remove fragment ({@code #...}) component — fragments must not appear in
 *       resource indicators per RFC 8707 §2.</li>
 *   <li>Remove trailing slash from the path unless the path is exactly {@code /}
 *       (i.e. root resource).</li>
 *   <li>Preserve query string as-is.</li>
 * </ul>
 *
 * <p>{@link #validate(String)} enforces RFC 8707 requirements:
 * <ul>
 *   <li>URI must have a scheme.</li>
 *   <li>URI must not contain a fragment.</li>
 * </ul>
 */
public final class CanonicalUri {

    private CanonicalUri() {}

    /**
     * Normalizes a URI string: lowercases scheme and host, strips the fragment,
     * and removes a trailing slash from non-root paths.
     *
     * @param uri the raw URI string
     * @return the normalized URI string
     * @throws IllegalArgumentException if {@code uri} is blank or cannot be parsed
     */
    public static String normalize(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("URI must not be blank");
        }
        URI parsed = parse(uri);

        String scheme = parsed.getScheme() != null ? parsed.getScheme().toLowerCase() : null;
        String host   = parsed.getHost()   != null ? parsed.getHost().toLowerCase()   : null;

        String path = parsed.getPath() != null ? parsed.getPath() : "";
        // Strip trailing slash unless path is exactly "/"
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        try {
            // Reconstruct without fragment
            URI normalized = new URI(
                    scheme,
                    parsed.getUserInfo(),
                    host,
                    parsed.getPort(),
                    path.isEmpty() ? null : path,
                    parsed.getQuery(),
                    null /* no fragment */);
            return normalized.toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot normalize URI: " + uri, e);
        }
    }

    /**
     * Validates that {@code uri} is acceptable as an RFC 8707 resource indicator.
     *
     * @param uri the URI string to validate
     * @return {@code true} if the URI is valid
     * @throws IllegalArgumentException if the URI is invalid, with a descriptive message
     */
    public static boolean validate(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Resource URI must not be blank");
        }
        URI parsed = parse(uri);
        if (parsed.getScheme() == null || parsed.getScheme().isBlank()) {
            throw new IllegalArgumentException("Resource URI must have a scheme: " + uri);
        }
        if (parsed.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Resource URI must not contain a fragment per RFC 8707: " + uri);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private static URI parse(String uri) {
        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed URI: " + uri, e);
        }
    }
}
