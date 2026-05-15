package com.nubian.ai.runtime.mcp.resources;

/**
 * Informational constants for well-known URI schemes used with MCP resources.
 *
 * <p>These constants are non-prescriptive: MCP servers may use any RFC 3986-compliant
 * URI scheme. This class exists for discoverability and to reduce magic strings in
 * client code.
 */
public final class UriSchemes {

    /** Standard filesystem (or virtual filesystem) resources. */
    public static final String FILE = "file://";

    /** Web resources the client can fetch directly without going through MCP. */
    public static final String HTTPS = "https://";

    /** Git repository resources. */
    public static final String GIT = "git://";

    private UriSchemes() {}

    /**
     * Returns {@code true} if the given URI starts with one of the three standard
     * MCP schemes ({@code file://}, {@code https://}, {@code git://}).
     *
     * <p>This check is informational only; non-standard schemes are fully valid
     * as long as they comply with RFC 3986.
     *
     * @param uri the URI string to test (may be null)
     * @return true when the URI uses a standard MCP scheme
     */
    public static boolean isStandardScheme(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.startsWith(FILE) || uri.startsWith(HTTPS) || uri.startsWith(GIT);
    }
}
