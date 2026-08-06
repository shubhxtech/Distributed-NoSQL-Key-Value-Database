package com.kvstore.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Day 1–2 implementation: in-memory key-value store backed by a ConcurrentHashMap.
 *
 * <p><b>Properties:</b>
 * <ul>
 *   <li>All reads and writes are O(1) average.</li>
 *   <li>Fully thread-safe via ConcurrentHashMap semantics.</li>
 *   <li>Data does not survive process restart — no persistence yet (Week 1, Day 3+).</li>
 *   <li>Deletes write tombstones rather than removing entries, matching LSM semantics
 *       so the interface contract stays identical when we swap in the real engine.</li>
 * </ul>
 *
 * <p>This class is intentionally kept simple. Any performance-relevant logic
 * (bloom filters, compaction, caching) lives in the Week 2 {@code LsmStorageEngine}.
 */
public class InMemoryStorageEngine implements StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStorageEngine.class);

    /** Central store: key → most recent entry (value or tombstone). */
    private final ConcurrentHashMap<String, ValueEntry> store = new ConcurrentHashMap<>();

    /**
     * Global version counter — incremented on every Put or Delete.
     * Acts as a Lamport clock for ordering writes within a single node.
     */
    private final AtomicLong versionCounter = new AtomicLong(0L);

    // ─── Write Operations ───────────────────────────────────────────────────

    @Override
    public void put(String key, byte[] value) {
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("Value cannot be null — use delete() to remove a key");
        }

        long version = versionCounter.incrementAndGet();
        store.put(key, ValueEntry.of(value, version));
        log.debug("PUT  key='{}' version={} bytes={}", key, version, value.length);
    }

    @Override
    public void delete(String key) {
        validateKey(key);

        // Use compute() so we only write a tombstone if the key exists.
        // Deleting a non-existent key is a safe no-op.
        long version = versionCounter.incrementAndGet();
        store.compute(key, (k, existing) -> {
            if (existing == null || existing.tombstone()) {
                log.debug("DELETE key='{}' — key not present, skipping", k);
                // Return null to leave the map unchanged (ConcurrentHashMap semantics)
                return existing;
            }
            log.debug("DELETE key='{}' version={}", k, version);
            return ValueEntry.tombstone(version);
        });
    }

    // ─── Read Operations ────────────────────────────────────────────────────

    @Override
    public Optional<ValueEntry> get(String key) {
        validateKey(key);

        ValueEntry entry = store.get(key);
        if (entry == null || entry.tombstone()) {
            log.debug("GET  key='{}' → NOT FOUND", key);
            return Optional.empty();
        }
        log.debug("GET  key='{}' version={}", key, entry.version());
        return Optional.of(entry);
    }

    // ─── Stats ──────────────────────────────────────────────────────────────

    @Override
    public long size() {
        return store.values().stream()
                .filter(e -> !e.tombstone())
                .count();
    }

    /** Returns the current highest version number assigned by this node. */
    public long currentVersion() {
        return versionCounter.get();
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void close() {
        log.info("InMemoryStorageEngine shutting down. Live keys at close: {}", size());
        store.clear();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key must not be null or blank");
        }
    }
}
