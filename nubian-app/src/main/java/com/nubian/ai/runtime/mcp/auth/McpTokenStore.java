package com.nubian.ai.runtime.mcp.auth;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Storage contract for OAuth access tokens keyed by resource URI.
 *
 * <p>Implementations may be in-memory (see {@link InMemoryTokenStore}), file-backed,
 * or backed by a secure credential store. Only the in-memory variant is provided
 * in this slice; persistence is out of scope.
 */
public interface McpTokenStore {

    /**
     * Returns the stored token for {@code resource}, or {@link Optional#empty()} if
     * none exists or the token has already been removed.
     *
     * @param resource the canonical resource URI (RFC 8707)
     * @return an {@link Optional} containing the stored {@link AccessToken}
     */
    Optional<AccessToken> get(String resource);

    /**
     * Stores (or replaces) the access token for {@code resource}.
     *
     * @param resource the canonical resource URI
     * @param token    the token to store
     */
    void put(String resource, AccessToken token);

    /**
     * Removes the token for {@code resource}, if present.
     *
     * @param resource the canonical resource URI
     */
    void remove(String resource);

    // ------------------------------------------------------------------
    // Default implementation
    // ------------------------------------------------------------------

    /**
     * Thread-safe in-memory token store backed by a {@link ConcurrentHashMap}.
     *
     * <p>Tokens survive only for the lifetime of the JVM process. This is the
     * default implementation; a file-backed store will be added in a later slice.
     */
    final class InMemoryTokenStore implements McpTokenStore {

        private final ConcurrentHashMap<String, AccessToken> store = new ConcurrentHashMap<>();

        @Override
        public Optional<AccessToken> get(String resource) {
            return Optional.ofNullable(store.get(resource));
        }

        @Override
        public void put(String resource, AccessToken token) {
            store.put(resource, token);
        }

        @Override
        public void remove(String resource) {
            store.remove(resource);
        }
    }
}
