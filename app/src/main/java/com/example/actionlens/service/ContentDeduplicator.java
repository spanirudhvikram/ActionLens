package com.example.actionlens.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory, bounded, content-aware deduplicator with time-based expiration.
 *
 * Replaces persistent notification key checks with cryptographic content fingerprints:
 * 1. Fingerprint is a SHA-256 hash of (source + "::" + normalizedContent).
 * 2. Raw message text is NEVER stored in cache, logs, or persistent storage.
 * 3. Expired fingerprints older than the configured window are automatically purged.
 * 4. Cache capacity is strictly bounded (LRU eviction).
 */
public class ContentDeduplicator {

    public interface Clock {
        long currentTimeMillis();
    }

    public static final long DEFAULT_WINDOW_MILLIS = 10 * 60 * 1000L; // 10 minutes
    public static final int DEFAULT_MAX_CAPACITY = 200;

    private final long windowMillis;
    private final int maxCapacity;
    private final Clock clock;
    private final Map<String, Long> cache;

    public ContentDeduplicator() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_CAPACITY, System::currentTimeMillis);
    }

    public ContentDeduplicator(long windowMillis, int maxCapacity, Clock clock) {
        this.windowMillis = windowMillis > 0 ? windowMillis : DEFAULT_WINDOW_MILLIS;
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : DEFAULT_MAX_CAPACITY;
        this.clock = clock != null ? clock : System::currentTimeMillis;

        Map<String, Long> lru = new LinkedHashMap<String, Long>(this.maxCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > ContentDeduplicator.this.maxCapacity;
            }
        };
        this.cache = Collections.synchronizedMap(lru);
    }

    /**
     * Checks if this content has already been seen from this source within the time window.
     * If not seen, records the fingerprint with the current timestamp.
     *
     * @param source Originating source/app label (e.g. "WhatsApp", "com.whatsapp")
     * @param content Message content
     * @return true if duplicate (already seen within window); false if fresh/unseen
     */
    public synchronized boolean isDuplicateAndRegister(String source, String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        long now = clock.currentTimeMillis();
        evictExpired(now);

        String fingerprint = computeFingerprint(source, content);
        Long lastSeen = cache.get(fingerprint);
        if (lastSeen != null && (now - lastSeen) < windowMillis) {
            return true;
        }

        cache.put(fingerprint, now);
        return false;
    }

    /**
     * Query whether this content is currently in the deduplication window without updating timestamp.
     */
    public synchronized boolean isDuplicate(String source, String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        long now = clock.currentTimeMillis();
        String fingerprint = computeFingerprint(source, content);
        Long lastSeen = cache.get(fingerprint);
        return lastSeen != null && (now - lastSeen) < windowMillis;
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void clear() {
        cache.clear();
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    private void evictExpired(long now) {
        cache.entrySet().removeIf(entry -> (now - entry.getValue()) >= windowMillis);
    }

    /**
     * Computes a privacy-preserving SHA-256 fingerprint for a given source and content.
     */
    public static String computeFingerprint(String source, String content) {
        String cleanSource = source != null ? source.trim().toLowerCase(Locale.ROOT) : "";
        String cleanContent = content != null ? content.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") : "";
        String payload = cleanSource + "::" + cleanContent;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(payload.hashCode());
        }
    }
}
