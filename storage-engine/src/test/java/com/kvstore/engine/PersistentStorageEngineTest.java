package com.kvstore.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Crash-recovery and persistence tests for {@link PersistentStorageEngine}.
 *
 * <p>The key technique: create an engine, write data, call {@link PersistentStorageEngine#close()},
 * then create a NEW engine on the same data directory — this simulates a clean restart.
 * We also simulate a crash by NOT calling close before reopening.
 *
 * <p>Test coverage:
 * <ul>
 *   <li>Survival of PUT / DELETE across clean restart</li>
 *   <li>Overwrite: latest value survives restart</li>
 *   <li>Tombstone (delete) survives restart — key stays absent</li>
 *   <li>Re-write after delete survives restart</li>
 *   <li>10 000-key persistence test</li>
 *   <li>Crash simulation: engine not closed before reopening</li>
 * </ul>
 */
@DisplayName("PersistentStorageEngine — crash recovery")
class PersistentStorageEngineTest {

    @TempDir
    Path dataDir;

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private PersistentStorageEngine openEngine() {
        return new PersistentStorageEngine(dataDir);
    }

    // ─── Basic persistence ────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT persists across clean restart")
    void putSurvivesRestart() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            engine.put("user:1", "Alice".getBytes());
        }

        try (PersistentStorageEngine engine = openEngine()) {
            Optional<ValueEntry> entry = engine.get("user:1");
            assertThat(entry).isPresent();
            assertThat(new String(entry.get().value())).isEqualTo("Alice");
        }
    }

    @Test
    @DisplayName("DELETE persists across clean restart — key stays absent")
    void deleteSurvivesRestart() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            engine.put("user:2", "Bob".getBytes());
            engine.delete("user:2");
        }

        try (PersistentStorageEngine engine = openEngine()) {
            assertThat(engine.get("user:2")).isEmpty();
        }
    }

    @Test
    @DisplayName("Overwrite: only the latest value survives restart")
    void overwriteSurvivesRestart() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            engine.put("key", "first".getBytes());
            engine.put("key", "second".getBytes());
            engine.put("key", "third".getBytes());
        }

        try (PersistentStorageEngine engine = openEngine()) {
            Optional<ValueEntry> entry = engine.get("key");
            assertThat(entry).isPresent();
            assertThat(new String(entry.get().value())).isEqualTo("third");
        }
    }

    @Test
    @DisplayName("Re-write after delete: new value is visible after restart")
    void rewriteAfterDeleteSurvivesRestart() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            engine.put("k", "original".getBytes());
            engine.delete("k");
            engine.put("k", "reborn".getBytes());
        }

        try (PersistentStorageEngine engine = openEngine()) {
            Optional<ValueEntry> entry = engine.get("k");
            assertThat(entry).isPresent();
            assertThat(new String(entry.get().value())).isEqualTo("reborn");
        }
    }

    @Test
    @DisplayName("size() reflects live key count correctly after restart")
    void sizeSurvivesRestart() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            engine.put("a", "1".getBytes());
            engine.put("b", "2".getBytes());
            engine.put("c", "3".getBytes());
            engine.delete("b");
            assertThat(engine.size()).isEqualTo(2);
        }

        try (PersistentStorageEngine engine = openEngine()) {
            assertThat(engine.size()).isEqualTo(2);  // "a" and "c" survive
            assertThat(engine.get("a")).isPresent();
            assertThat(engine.get("b")).isEmpty();
            assertThat(engine.get("c")).isPresent();
        }
    }

    @Test
    @DisplayName("Multiple keys with independent values all survive restart")
    void multipleKeysSurviveRestart() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            for (int i = 0; i < 100; i++) {
                engine.put("ns:key:" + i, ("value-" + i).getBytes());
            }
        }

        try (PersistentStorageEngine engine = openEngine()) {
            assertThat(engine.size()).isEqualTo(100);
            for (int i = 0; i < 100; i++) {
                Optional<ValueEntry> entry = engine.get("ns:key:" + i);
                assertThat(entry).isPresent();
                assertThat(new String(entry.get().value())).isEqualTo("value-" + i);
            }
        }
    }

    // ─── Crash simulation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Crash simulation: WAL replayed on next open — data not lost")
    void crashSimulation() throws Exception {
        // Write 50 keys — do NOT close (simulating an abrupt process termination)
        PersistentStorageEngine engine = openEngine();
        for (int i = 0; i < 50; i++) {
            engine.put("crash:key:" + i, ("v" + i).getBytes());
        }
        // Intentionally skip engine.close() to simulate crash
        // (WalWriter.append() already fsync'd each record, so data is safe)

        // Reopen and verify all 50 keys are present
        try (PersistentStorageEngine recovered = openEngine()) {
            assertThat(recovered.size()).isEqualTo(50);
            for (int i = 0; i < 50; i++) {
                assertThat(recovered.get("crash:key:" + i)).isPresent();
            }
        }
    }

    @Test
    @DisplayName("Fresh data dir: engine starts clean with size 0")
    void freshStart() throws IOException {
        Path freshDir = dataDir.resolve("fresh");
        PersistentStorageEngine engine = new PersistentStorageEngine(freshDir);
        assertThat(engine.size()).isZero();
        assertThat(engine.get("anything")).isEmpty();
    }

    // ─── Input validation (same guards as InMemoryStorageEngine) ─────────────

    @Test
    @DisplayName("put() with null key throws IllegalArgumentException")
    void putNullKey() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            assertThatThrownBy(() -> engine.put(null, "v".getBytes()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("put() with null value throws IllegalArgumentException")
    void putNullValue() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            assertThatThrownBy(() -> engine.put("k", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("delete() with null key throws IllegalArgumentException")
    void deleteNullKey() throws Exception {
        try (PersistentStorageEngine engine = openEngine()) {
            assertThatThrownBy(() -> engine.delete(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ─── Bulk / stress ────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 000-key persistence: all keys correct after restart")
    void tenThousandKeyPersistence() throws Exception {
        int N = 10_000;

        try (PersistentStorageEngine engine = openEngine()) {
            for (int i = 0; i < N; i++) {
                engine.put("key:" + i, ("value:" + i).getBytes());
            }
            assertThat(engine.size()).isEqualTo(N);
        }

        try (PersistentStorageEngine engine = openEngine()) {
            assertThat(engine.size()).isEqualTo(N);
            for (int i = 0; i < N; i++) {
                Optional<ValueEntry> entry = engine.get("key:" + i);
                assertThat(entry).as("key:%d should be present", i).isPresent();
                assertThat(new String(entry.get().value()))
                        .as("key:%d value mismatch", i)
                        .isEqualTo("value:" + i);
            }
        }
    }

    @Test
    @DisplayName("WAL versions increase monotonically after recovery")
    void versionsIncreaseMonotonically() throws Exception {
        long lastVersion;

        try (PersistentStorageEngine engine = openEngine()) {
            engine.put("k", "v1".getBytes());
            lastVersion = engine.get("k").get().version();
        }

        try (PersistentStorageEngine engine = openEngine()) {
            engine.put("k", "v2".getBytes());
            long newVersion = engine.get("k").get().version();
            assertThat(newVersion).isGreaterThan(lastVersion);
        }
    }
}
