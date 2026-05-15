package com.nubian.ai.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Per-run cache for checkpoint-verifier verdicts.
 *  Key: checkpointId + sha256(screenshotPng). If the same checkpoint is asked
 *  about an identical screenshot twice, the second ask returns the cached
 *  verdict instead of paying for another LLM call. Strict equality only —
 *  one-byte change in the screenshot is a miss. */
public final class VerdictCache {

    private static final Logger log = LoggerFactory.getLogger(VerdictCache.class);
    private static final int DEFAULT_CAPACITY = 256;

    private final Map<String, CachedVerdict> cache;

    public VerdictCache() {
        this(DEFAULT_CAPACITY);
    }

    public VerdictCache(int capacity) {
        int cap = Math.max(8, capacity);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, CachedVerdict>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedVerdict> eldest) {
                return size() > cap;
            }
        });
    }

    /** Build a cache key. Returns {@code null} when the checkpointId is blank — empty
     *  keys would let unrelated calls share a verdict and have already been observed
     *  masking the "verify with no active checkpoint" stuck-state bug. {@code get}/
     *  {@code put} treat a null key as "do not cache, do not look up". */
    public String key(String checkpointId, byte[] screenshotPng) {
        if (checkpointId == null || checkpointId.isBlank()) {
            log.warn("[VerdictCache] refusing to build key for blank checkpointId; "
                    + "upstream is asking the cache about an empty target");
            return null;
        }
        return checkpointId + ":" + sha256Hex(screenshotPng);
    }

    public Optional<CachedVerdict> get(String key) {
        if (key == null || key.isBlank() || key.startsWith(":")) return Optional.empty();
        return Optional.ofNullable(cache.get(key));
    }

    public void put(String key, boolean ok, String reason) {
        if (key == null || key.isBlank() || key.startsWith(":")) return;
        cache.put(key, new CachedVerdict(ok, reason == null ? "" : reason));
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    public record CachedVerdict(boolean ok, String reason) {}

    private static String sha256Hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "empty";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
