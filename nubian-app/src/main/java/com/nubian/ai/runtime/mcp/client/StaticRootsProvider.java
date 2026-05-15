package com.nubian.ai.runtime.mcp.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link RootsProvider} backed by an in-memory list that can be replaced at runtime.
 *
 * <p>The internal list is a {@link CopyOnWriteArrayList}, so reads are lock-free and
 * callers of {@link #listRoots()} always get a point-in-time snapshot. Writes via
 * {@link #setRoots(List)} are thread-safe and fire all registered change callbacks.
 *
 * <p>Usage:
 * <pre>{@code
 * StaticRootsProvider provider = new StaticRootsProvider(List.of(
 *         new Root("file:///workspace/project-a", "Project A")));
 *
 * // later, when the workspace changes:
 * provider.setRoots(List.of(
 *         new Root("file:///workspace/project-a", "Project A"),
 *         new Root("file:///workspace/project-b", "Project B")));
 * // → triggers notifications/roots/list_changed on all registered callbacks
 * }</pre>
 */
public final class StaticRootsProvider implements RootsProvider {

    private final CopyOnWriteArrayList<Root> roots;
    private final CopyOnWriteArrayList<Runnable> changeCallbacks = new CopyOnWriteArrayList<>();

    /** Creates a provider with the given initial root list. */
    public StaticRootsProvider(List<Root> initialRoots) {
        this.roots = new CopyOnWriteArrayList<>(
                initialRoots != null ? initialRoots : List.of());
    }

    /** Creates an empty provider (no roots). */
    public StaticRootsProvider() {
        this(List.of());
    }

    /**
     * Replaces the current root list and fires all registered change callbacks.
     *
     * @param newRoots The replacement list. {@code null} is treated as empty.
     */
    public void setRoots(List<Root> newRoots) {
        List<Root> replacement = newRoots != null ? new ArrayList<>(newRoots) : List.of();
        roots.clear();
        roots.addAll(replacement);
        for (Runnable cb : changeCallbacks) {
            cb.run();
        }
    }

    @Override
    public List<Root> listRoots() {
        return List.copyOf(roots);
    }

    @Override
    public void onListChanged(Runnable callback) {
        if (callback != null) {
            changeCallbacks.add(callback);
        }
    }
}
