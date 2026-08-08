package com.kvstore.engine;

import com.kvstore.engine.wal.WalEntry;
import com.kvstore.engine.wal.WalOperation;
import com.kvstore.engine.wal.WalReader;
import com.kvstore.engine.wal.WalWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Day 3–4: Durable storage engine that survives process crashes.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   PUT / DELETE
 *       │
 *       ▼
 *   WalWriter  ← fsync to disk FIRST (durability guarantee)
 *       │
 *       ▼
 *   InMemoryStorageEngine  ← in-memory state (fast reads)
 *
 *   GET always served from InMemoryStorageEngine (never touches disk for reads).
 * </pre>
 *
 * <h2>Crash recovery</h2>
 * <p>On construction:
 * <ol>
 *   <li>Creates the data directory if needed.</li>
 *   <li>Calls {@link WalReader#readAll} to load all verified WAL entries.</li>
 *   <li>Replays each entry into a fresh {@link InMemoryStorageEngine},
 *       restoring the exact pre-crash state.</li>
 *   <li>Opens a new {@link WalWriter} (append mode) for subsequent writes.</li>
 * </ol>
 *
 * <h2>Why WAL before memtable?</h2>
 * <p>If we wrote to the memtable first and then crashed before the WAL write,
 * the data would exist in memory but not on disk — it would be silently lost
 * on restart. Writing to the WAL first (and syncing to disk) ensures that any
 * write for which the caller received a success response is recoverable.
 *
 * <h2>Future evolution (Week 2)</h2>
 * <ul>
 *   <li>When the memtable exceeds a size threshold, it is flushed to an SSTable
 *       file on disk and the WAL segment is truncated — reads shift to SSTables.</li>
 *   <li>This class will be superseded by {@code LsmStorageEngine} which adds the
 *       full Memtable → SSTable flush + compaction pipeline.</li>
 * </ul>
 */
public class PersistentStorageEngine implements StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(PersistentStorageEngine.class);

    /** The in-memory state — always reflects the post-replay + live-writes state. */
    private final InMemoryStorageEngine memStore;
    /** Appends new writes; opened AFTER replay so it doesn't interfere with reading. */
    private final WalWriter walWriter;
    private final Path dataDir;
    private volatile boolean closed = false;

    /**
     * Constructs (and if necessary recovers) the storage engine.
     *
     * @param dataDir directory where {@code wal.log} is stored; created if absent.
     * @throws StorageException if the directory cannot be created or the WAL cannot be opened.
     */
    public PersistentStorageEngine(Path dataDir) {
        this.dataDir = dataDir;

        try {
            Files.createDirectories(dataDir);
            log.info("PersistentStorageEngine starting. Data dir: {}", dataDir.toAbsolutePath());

            this.memStore = new InMemoryStorageEngine();

            // Replay any existing WAL before opening the writer
            Path walPath = walPath();
            int recovered = replayWal(walPath);
            log.info("WAL replay done. Recovered {} operations. Live keys: {}",
                    recovered, memStore.size());

            // Open the writer in append mode for subsequent writes
            this.walWriter = new WalWriter(walPath);

        } catch (IOException e) {
            throw new StorageException("Failed to initialise PersistentStorageEngine at: " + dataDir, e);
        }
    }

    // ─── Write Operations ────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Write order: WAL (durable) → memtable (in-memory).
     * If the JVM crashes after the WAL write but before the memtable write,
     * the WAL replay on the next start will apply the write correctly.
     */
    @Override
    public void put(String key, byte[] value) {
        validateKey(key);
        if (value == null) throw new IllegalArgumentException("Value must not be null");

        walWriter.append(WalEntry.put(key, value));  // ← durable first
        memStore.put(key, value);                    // ← then memory
    }

    /**
     * {@inheritDoc}
     *
     * <p>Write order: WAL tombstone (durable) → memtable tombstone (in-memory).
     */
    @Override
    public void delete(String key) {
        validateKey(key);
        walWriter.append(WalEntry.delete(key));      // ← durable first
        memStore.delete(key);                        // ← then memory
    }

    // ─── Read Operations ─────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Reads are always served from the in-memory store.
     * No disk I/O on the read path (until we add SSTables in Week 2).
     */
    @Override
    public Optional<ValueEntry> get(String key) {
        return memStore.get(key);
    }

    @Override
    public long size() {
        return memStore.size();
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        log.info("PersistentStorageEngine shutting down. Live keys: {}", size());
        walWriter.close();
        memStore.close();
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Path walPath() {
        return dataDir.resolve("wal.log");
    }

    /**
     * Replays the WAL into the in-memory store. Returns the number of operations replayed.
     */
    private int replayWal(Path walPath) throws IOException {
        List<WalEntry> entries = WalReader.readAll(walPath);
        for (WalEntry entry : entries) {
            switch (entry.operation()) {
                case PUT    -> memStore.put(entry.key(), entry.value());
                case DELETE -> memStore.delete(entry.key());
            }
        }
        return entries.size();
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key must not be null or blank");
        }
    }
}
