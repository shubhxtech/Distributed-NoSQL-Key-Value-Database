package com.kvstore.engine;

import java.util.Arrays;

/**
 * Immutable record representing a stored value entry.
 *
 * <p>Every write produces a new ValueEntry with a monotonically increasing version.
 * Deletes produce a tombstone entry ({@code tombstone = true, value = null}).
 *
 * <p>Design note: {@code byte[]} inside a record does not have structural equals/hashCode,
 * but for the storage engine we compare by version/key, not by value bytes — so this is fine.
 *
 * @param value       Raw value bytes. {@code null} when this is a tombstone.
 * @param version     Monotonic version counter — incremented on every Put or Delete.
 * @param timestampMs Wall-clock time (epoch ms) when this entry was written.
 * @param tombstone   {@code true} if this entry represents a deletion.
 */
public record ValueEntry(
        byte[]  value,
        long    version,
        long    timestampMs,
        boolean tombstone
) {

    /**
     * Creates a live value entry.
     */
    public static ValueEntry of(byte[] value, long version) {
        return new ValueEntry(value, version, System.currentTimeMillis(), false);
    }

    /**
     * Creates a tombstone (deletion marker) entry.
     */
    public static ValueEntry tombstone(long version) {
        return new ValueEntry(null, version, System.currentTimeMillis(), true);
    }

    /**
     * Returns a human-readable summary (does not print raw bytes).
     */
    @Override
    public String toString() {
        if (tombstone) {
            return "ValueEntry[TOMBSTONE version=%d ts=%d]".formatted(version, timestampMs);
        }
        return "ValueEntry[valueLen=%d version=%d ts=%d]"
                .formatted(value == null ? 0 : value.length, version, timestampMs);
    }

    /**
     * Structural equality based on content, not reference.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ValueEntry other)) return false;
        return version == other.version
                && timestampMs == other.timestampMs
                && tombstone == other.tombstone
                && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(version);
        result = 31 * result + Long.hashCode(timestampMs);
        result = 31 * result + Boolean.hashCode(tombstone);
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }
}
