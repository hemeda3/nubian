package com.nubian.ai.runtime.mcp.lifecycle;

import java.util.List;
import java.util.Set;

/**
 * MCP protocol version constants.
 *
 * The canonical version this client sends in {@code initialize} is {@link #CURRENT}.
 * {@link #SUPPORTED} enumerates all versions we will accept from a server response;
 * anything outside this set is grounds for disconnection per the spec ("client SHOULD
 * disconnect if it does not support the version").
 */
public final class ProtocolVersion {

    /** The version this client advertises in every {@code initialize} request. */
    public static final String CURRENT = "2025-11-25";

    /**
     * All protocol versions this client will accept in a server's {@code initialize}
     * response, including compatibility back-compat versions.
     */
    public static final List<String> SUPPORTED = List.of(
            "2025-11-25",
            "2025-03-26",
            "2024-11-05"
    );

    /** Fast O(1) membership check against {@link #SUPPORTED}. */
    public static final Set<String> SUPPORTED_SET = Set.copyOf(SUPPORTED);

    /** Returns true if the given version string is one we accept. */
    public static boolean isSupported(String version) {
        return version != null && SUPPORTED_SET.contains(version);
    }

    private ProtocolVersion() {
    }
}
