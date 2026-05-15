package com.nubian.ai.runtime.mcp.client;

import java.util.List;

/**
 * Strategy interface for supplying the set of filesystem roots this client exposes.
 *
 * <p>Implementations decide which directories or URIs are in-bounds for connected
 * MCP servers. The built-in default is {@link StaticRootsProvider}; host applications
 * may supply their own implementation (e.g. project-aware, user-configurable).
 *
 * <p>When the root list changes the implementation SHOULD invoke any registered
 * change-callback so that {@link RootsHandler} can emit a
 * {@code notifications/roots/list_changed} notification to the server.
 */
public interface RootsProvider {

    /**
     * Returns the current list of roots.
     *
     * @return an immutable snapshot of roots; never {@code null}.
     */
    List<Root> listRoots();

    /**
     * Registers a callback that will be invoked (on any thread) whenever the root
     * list changes. Multiple callbacks may be registered.
     *
     * <p>The default implementation is a no-op (for providers whose list never changes).
     *
     * @param callback Runnable fired on every change; must be cheap (e.g. enqueue a task).
     */
    default void onListChanged(Runnable callback) {
        // no-op for static / immutable providers
    }
}
