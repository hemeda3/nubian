package com.nubian.ai.app;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VerdictCacheTest {

    @Test
    void identicalScreenshotAndCheckpointReturnsCachedVerdict() {
        VerdictCache cache = new VerdictCache();
        byte[] shot = new byte[]{1, 2, 3, 4};

        String key = cache.key("1.1", shot);
        cache.put(key, true, "looks ok");

        Optional<VerdictCache.CachedVerdict> hit = cache.get(cache.key("1.1", shot));
        assertTrue(hit.isPresent());
        assertTrue(hit.get().ok());
        assertEquals("looks ok", hit.get().reason());
    }

    @Test
    void oneByteDifferenceMissesCache() {
        VerdictCache cache = new VerdictCache();
        cache.put(cache.key("1.1", new byte[]{1, 2, 3}), true, "ok");

        Optional<VerdictCache.CachedVerdict> miss = cache.get(cache.key("1.1", new byte[]{1, 2, 4}));
        assertTrue(miss.isEmpty());
    }

    @Test
    void differentCheckpointMissesCache() {
        VerdictCache cache = new VerdictCache();
        byte[] shot = new byte[]{9, 9};
        cache.put(cache.key("1.1", shot), true, "ok");

        Optional<VerdictCache.CachedVerdict> miss = cache.get(cache.key("1.2", shot));
        assertTrue(miss.isEmpty());
    }

    @Test
    void clearEvictsAllEntries() {
        VerdictCache cache = new VerdictCache();
        cache.put(cache.key("a", new byte[]{1}), true, "");
        cache.put(cache.key("b", new byte[]{1}), false, "");
        assertEquals(2, cache.size());

        cache.clear();
        assertEquals(0, cache.size());
    }
}
