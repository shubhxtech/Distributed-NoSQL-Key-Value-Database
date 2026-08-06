package com.kvstore.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link InMemoryStorageEngine}.
 *
 * Coverage:
 * - Basic Put / Get / Delete semantics
 * - Tombstone behaviour (deleted key is invisible)
 * - Overwrite semantics (version increments)
 * - Edge cases: blank key, null value, double delete
 * - Size accounting
 */
@DisplayName("InMemoryStorageEngine")
class InMemoryStorageEngineTest {

    private InMemoryStorageEngine engine;

    @BeforeEach
    void setUp() {
        engine = new InMemoryStorageEngine();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // ─── Put & Get ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("put() and get()")
    class PutAndGet {

        @Test
        @DisplayName("get() on empty store returns empty Optional")
        void getOnEmptyStore() {
            assertThat(engine.get("missing")).isEmpty();
        }

        @Test
        @DisplayName("put() then get() returns the stored value")
        void putThenGet() {
            engine.put("user:1", bytes("Alice"));

            Optional<ValueEntry> result = engine.get("user:1");
            assertThat(result).isPresent();
            assertThat(str(result.get().value())).isEqualTo("Alice");
        }

        @Test
        @DisplayName("put() assigns a positive, incrementing version")
        void versionIncrements() {
            engine.put("k1", bytes("v1"));
            engine.put("k2", bytes("v2"));

            long v1 = engine.get("k1").get().version();
            long v2 = engine.get("k2").get().version();

            assertThat(v1).isPositive();
            assertThat(v2).isGreaterThan(v1);
        }

        @Test
        @DisplayName("overwriting a key updates value and bumps version")
        void overwrite() {
            engine.put("key", bytes("original"));
            long firstVersion = engine.get("key").get().version();

            engine.put("key", bytes("updated"));
            Optional<ValueEntry> updated = engine.get("key");

            assertThat(updated).isPresent();
            assertThat(str(updated.get().value())).isEqualTo("updated");
            assertThat(updated.get().version()).isGreaterThan(firstVersion);
        }

        @Test
        @DisplayName("multiple keys coexist independently")
        void multipleKeys() {
            engine.put("a", bytes("alpha"));
            engine.put("b", bytes("beta"));
            engine.put("c", bytes("gamma"));

            assertThat(str(engine.get("a").get().value())).isEqualTo("alpha");
            assertThat(str(engine.get("b").get().value())).isEqualTo("beta");
            assertThat(str(engine.get("c").get().value())).isEqualTo("gamma");
        }
    }

    // ─── Delete ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("deleted key returns empty on get()")
        void deleteMakesKeyInvisible() {
            engine.put("session:42", bytes("token"));
            engine.delete("session:42");

            assertThat(engine.get("session:42")).isEmpty();
        }

        @Test
        @DisplayName("deleting a non-existent key is a safe no-op")
        void deleteNonExistentKey() {
            assertThatCode(() -> engine.delete("ghost")).doesNotThrowAnyException();
            assertThat(engine.get("ghost")).isEmpty();
        }

        @Test
        @DisplayName("double delete is idempotent")
        void doubleDelete() {
            engine.put("x", bytes("value"));
            engine.delete("x");
            assertThatCode(() -> engine.delete("x")).doesNotThrowAnyException();
            assertThat(engine.get("x")).isEmpty();
        }

        @Test
        @DisplayName("re-writing a deleted key makes it visible again")
        void rewriteAfterDelete() {
            engine.put("key", bytes("first"));
            engine.delete("key");
            engine.put("key", bytes("second"));

            Optional<ValueEntry> result = engine.get("key");
            assertThat(result).isPresent();
            assertThat(str(result.get().value())).isEqualTo("second");
        }
    }

    // ─── Size ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("size()")
    class Size {

        @Test
        @DisplayName("empty engine has size 0")
        void emptySize() {
            assertThat(engine.size()).isZero();
        }

        @Test
        @DisplayName("size reflects live key count, not tombstones")
        void sizeIgnoresTombstones() {
            engine.put("a", bytes("1"));
            engine.put("b", bytes("2"));
            engine.put("c", bytes("3"));
            engine.delete("b");

            // b is deleted → only a and c are live
            assertThat(engine.size()).isEqualTo(2L);
        }
    }

    // ─── Input Validation ────────────────────────────────────────────────────

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @Test
        @DisplayName("put() with null key throws IllegalArgumentException")
        void putNullKey() {
            assertThatThrownBy(() -> engine.put(null, bytes("v")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("put() with blank key throws IllegalArgumentException")
        void putBlankKey() {
            assertThatThrownBy(() -> engine.put("   ", bytes("v")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("put() with null value throws IllegalArgumentException")
        void putNullValue() {
            assertThatThrownBy(() -> engine.put("k", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("get() with null key throws IllegalArgumentException")
        void getNullKey() {
            assertThatThrownBy(() -> engine.get(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("delete() with null key throws IllegalArgumentException")
        void deleteNullKey() {
            assertThatThrownBy(() -> engine.delete(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ─── Bulk Write ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("bulk: 10_000 puts then 10_000 gets — all present and correct")
    void bulkPutGet() {
        int count = 10_000;
        for (int i = 0; i < count; i++) {
            engine.put("key:" + i, bytes("value:" + i));
        }

        assertThat(engine.size()).isEqualTo(count);

        for (int i = 0; i < count; i++) {
            Optional<ValueEntry> entry = engine.get("key:" + i);
            assertThat(entry).as("key:%d should be present", i).isPresent();
            assertThat(str(entry.get().value())).isEqualTo("value:" + i);
        }
    }
}
