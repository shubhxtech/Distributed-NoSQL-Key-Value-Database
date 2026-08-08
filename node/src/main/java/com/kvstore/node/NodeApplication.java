package com.kvstore.node;

import com.kvstore.engine.PersistentStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.node.config.NodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/**
 * Entry point for the KV storage node.
 *
 * <p>Each node is an independent storage unit that:
 * <ol>
 *   <li>Owns a slice of the key-space (determined by the coordinator's hash ring).</li>
 *   <li>Exposes a gRPC {@code KvService} for Put / Get / Delete operations.</li>
 *   <li>Exposes an HTTP actuator endpoint for health checks and Prometheus metrics.</li>
 * </ol>
 *
 * <p><b>Day 1–2:</b> backed by {@code InMemoryStorageEngine} (no persistence).<br>
 * <b>Day 3–4:</b> backed by {@link PersistentStorageEngine} (WAL — survives crashes).<br>
 * <b>Week 2:</b> will swap to {@code LsmStorageEngine} (WAL + Memtable + SSTables).
 */
@SpringBootApplication
@EnableConfigurationProperties(NodeProperties.class)
public class NodeApplication {

    private static final Logger log = LoggerFactory.getLogger(NodeApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NodeApplication.class, args);
    }

    /**
     * Provides the {@link StorageEngine} bean used by the gRPC service.
     *
     * <p>Day 3–4: {@link PersistentStorageEngine} — writes are fsync'd to a WAL file
     * before the in-memory state is updated. On startup the WAL is replayed to
     * restore the pre-crash state. Zero data loss for any acknowledged write.
     *
     * <p>To swap in the full LSM engine (Week 2), change the return statement here only.
     */
    @Bean
    public StorageEngine storageEngine(NodeProperties props) {
        Path dataDir = Path.of(props.dataDir());
        log.info("Initializing PersistentStorageEngine for node '{}' at '{}'",
                props.id(), dataDir.toAbsolutePath());
        // Week 2: return new LsmStorageEngine(dataDir);
        return new PersistentStorageEngine(dataDir);
    }
}

