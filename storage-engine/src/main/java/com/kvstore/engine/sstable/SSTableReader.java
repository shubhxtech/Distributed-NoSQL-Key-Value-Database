package com.kvstore.engine.sstable;

import com.kvstore.engine.StorageException;
import com.kvstore.engine.ValueEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Day 5: Reads a single SSTable file and serves point-lookup queries.
 *
 * <h2>Read algorithm</h2>
 * <ol>
 *   <li><b>Open:</b> Read the footer (last 20 bytes) to get the index block offset
 *       and total entry count.</li>
 *   <li><b>Load index:</b> Read the sparse index block entirely into memory.
 *       Typically {@code entryCount / INDEX_INTERVAL} entries — a few hundred KB at most.</li>
 *   <li><b>GET(key):</b> Binary-search the index to find the largest index key
 *       that is {@code ≤ target}. Seek to that data-block offset. Scan forward
 *       linearly until the key is found or the next key exceeds the target.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>Not thread-safe: each caller should hold its own {@code SSTableReader} or
 * synchronize externally. {@link com.kvstore.engine.LsmStorageEngine} serialises
 * reads through its own concurrency model.
 *
 * <h2>Resource lifecycle</h2>
 * <p>Implements {@link Closeable}. The underlying {@link RandomAccessFile} is kept
 * open for the lifetime of the reader so we avoid repeated open/close costs on the
 * hot read path.
 */
public class SSTableReader implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SSTableReader.class);

    private final SSTableMetadata metadata;
    private final RandomAccessFile raf;

    // In-memory sparse index — loaded once on construction
    private final List<String> indexKeys    = new ArrayList<>();
    private final List<Long>   indexOffsets = new ArrayList<>();

    /**
     * Metadata that may be updated after index load to reflect real firstKey/lastKey.
     * Use this field (not the constructor parameter) for range checks.
     */
    private SSTableMetadata effectiveMetadata;

    /**
     * Opens the SSTable file and loads the sparse index into memory.
     *
     * @param metadata Metadata describing the SSTable file.
     * @throws StorageException if the file cannot be read or has an invalid footer.
     */
    public SSTableReader(SSTableMetadata metadata) {
        this.metadata = metadata;
        this.effectiveMetadata = metadata;
        try {
            this.raf = new RandomAccessFile(metadata.path().toFile(), "r");
            loadIndex();
            // effectiveMetadata is updated inside loadIndex() with real firstKey/lastKey
        } catch (IOException e) {
            throw new StorageException("Cannot open SSTable: " + metadata.path(), e);
        }
        log.debug("SSTableReader opened: {} ({} index entries, range [{} … {}])",
                metadata.filename(), indexKeys.size(),
                effectiveMetadata.firstKey(), effectiveMetadata.lastKey());
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Looks up a key in this SSTable.
     *
     * @param key the key to search for.
     * @return the most-recent {@link ValueEntry} for {@code key} (may be a tombstone),
     *         or empty if the key is not in this SSTable.
     */
    public Optional<ValueEntry> get(String key) {
        // Fast range pre-check using effectiveMetadata (has real firstKey/lastKey)
        if (!effectiveMetadata.mightContain(key)) {
            return Optional.empty();
        }

        // Binary search the sparse index to find the nearest preceding key
        long dataOffset = findDataOffset(key);
        if (dataOffset < 0) {
            return Optional.empty();
        }

        // Scan forward from that data offset
        return scanForKey(key, dataOffset);
    }

    public SSTableMetadata metadata() {
        return effectiveMetadata;
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    // ─── Private: index loading + metadata derivation ─────────────────────────

    /**
     * Reads the footer to get the index offset, then reads all index entries
     * into {@link #indexKeys} and {@link #indexOffsets}.
     */
    private void loadIndex() throws IOException {
        long fileLen = raf.length();
        if (fileLen < SSTableWriter.FOOTER_SIZE) {
            throw new StorageException("SSTable too small to contain a footer: " + metadata.path());
        }

        // ── Read footer (last 20 bytes) ───────────────────────────────────────
        raf.seek(fileLen - SSTableWriter.FOOTER_SIZE);
        long indexBlockOffset = raf.readLong();
        int  totalEntryCount  = raf.readInt();
        long magic            = raf.readLong();

        if (magic != SSTableWriter.MAGIC) {
            throw new StorageException(
                    "SSTable magic mismatch in " + metadata.path() +
                    " (expected 0x%X, got 0x%X)".formatted(SSTableWriter.MAGIC, magic));
        }

        log.debug("SSTable footer: indexOffset={}, entries={}", indexBlockOffset, totalEntryCount);

        // ── Read index block ──────────────────────────────────────────────────
        raf.seek(indexBlockOffset);
        int indexEntryCount = raf.readInt();
        for (int i = 0; i < indexEntryCount; i++) {
            int keyLen   = raf.readInt();
            byte[] keyB  = new byte[keyLen];
            raf.readFully(keyB);
            long offset  = raf.readLong();
            indexKeys.add(new String(keyB, StandardCharsets.UTF_8));
            indexOffsets.add(offset);
        }

        if (!indexKeys.isEmpty()) {
            // firstKey is the first index key (since data is sorted ascending)
            String firstKey = indexKeys.get(0);

            // lastKey: scan forward from the LAST index entry to find the actual
            // last data key. The last index entry covers keys up to INDEX_INTERVAL-1
            // entries beyond it, so the real last key may be past the last index key.
            String lastKey = findLastDataKey(indexOffsets.get(indexOffsets.size() - 1), indexBlockOffset);

            this.effectiveMetadata = new SSTableMetadata(
                metadata.path(),
                totalEntryCount,
                firstKey,
                lastKey,
                metadata.createdAtMs(),
                metadata.sizeBytes()
            );
        }
    }

    /**
     * Scans forward from {@code startDataOffset} through data entries (stopping at
     * {@code indexBlockOffset}) to find the last key written in the data block.
     */
    private String findLastDataKey(long startDataOffset, long indexBlockOffset) {
        String lastKey = indexKeys.get(indexKeys.size() - 1); // safe fallback
        try {
            raf.seek(startDataOffset);
            while (raf.getFilePointer() < indexBlockOffset) {
                int keyLen;
                try { keyLen = raf.readInt(); } catch (EOFException e) { break; }
                if (keyLen <= 0 || keyLen > 4096) break;
                byte[] kb = new byte[keyLen];
                raf.readFully(kb);
                lastKey = new String(kb, StandardCharsets.UTF_8);
                byte flags   = raf.readByte();
                int valueLen = raf.readInt();
                if (valueLen > 0) raf.skipBytes(valueLen);
                raf.skipBytes(8); // version long
            }
        } catch (IOException e) {
            log.warn("Could not derive lastKey accurately: {}", e.getMessage());
        }
        return lastKey;
    }

    // ─── Private: lookup ──────────────────────────────────────────────────────

    /**
     * Binary-searches the sparse index to find the data-block offset of the
     * largest index key that is {@code ≤ target}.
     *
     * @return data-block offset, or {@code -1} if target is before all index keys.
     */
    private long findDataOffset(String target) {
        if (indexKeys.isEmpty()) return 0L;

        int lo = 0, hi = indexKeys.size() - 1, result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = indexKeys.get(mid).compareTo(target);
            if (cmp <= 0) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return (result < 0) ? 0L : indexOffsets.get(result);
    }

    /**
     * Linearly scans the data block from {@code startOffset}, comparing each key
     * against {@code target}. Stops as soon as the current key exceeds the target
     * (keys are sorted, so no match is possible beyond this point).
     */
    private Optional<ValueEntry> scanForKey(String target, long startOffset) {
        try {
            raf.seek(startOffset);

            while (raf.getFilePointer() < metadata.sizeBytes() - SSTableWriter.FOOTER_SIZE) {
                // Peek: try to read the next key
                int keyLen;
                try {
                    keyLen = raf.readInt();
                } catch (EOFException e) {
                    break;
                }

                // Safety guard against corrupt index pointing into non-data area
                if (keyLen <= 0 || keyLen > 4096) break;

                byte[] keyBytes = new byte[keyLen];
                raf.readFully(keyBytes);
                String currentKey = new String(keyBytes, StandardCharsets.UTF_8);

                int cmp = currentKey.compareTo(target);

                byte flags    = raf.readByte();
                int  valueLen = raf.readInt();
                byte[] value  = null;
                if (valueLen > 0) {
                    value = new byte[valueLen];
                    raf.readFully(value);
                }
                long version = raf.readLong();

                if (cmp == 0) {
                    // Found — build the ValueEntry
                    boolean tombstone = (flags & SSTableWriter.FLAG_TOMBSTONE) != 0;
                    return Optional.of(new ValueEntry(tombstone ? null : value,
                            version,
                            System.currentTimeMillis(),
                            tombstone));
                }

                if (cmp > 0) {
                    // Current key has passed target — no match in sorted order
                    break;
                }
                // cmp < 0 → keep scanning forward
            }
        } catch (IOException e) {
            throw new StorageException("Error reading SSTable " + metadata.path(), e);
        }
        return Optional.empty();
    }

    // ─── Factory ──────────────────────────────────────────────────────────────

    /**
     * Convenience factory: opens an existing SSTable from its file path.
     * Derives metadata from the file.
     */
    public static SSTableReader open(Path path) throws IOException {
        long size = Files.size(path);
        // Metadata firstKey/lastKey are unknown when loading existing files at startup.
        // We use placeholder values — they will be populated from the index on open.
        SSTableMetadata placeholder = new SSTableMetadata(path, 0, "", "", 0L, size);
        SSTableReader reader = new SSTableReader(placeholder);
        // Derive firstKey/lastKey from the loaded index
        if (!reader.indexKeys.isEmpty()) {
            // We don't have a direct way to patch the record, so we create a new one.
            // For startup loading we'll just return the reader with the placeholder.
        }
        return reader;
    }
}
