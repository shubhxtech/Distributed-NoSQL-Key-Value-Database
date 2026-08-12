package com.kvstore.node;

import com.kvstore.engine.LsmStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.engine.lsm.SkipListMemtable;
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
 * <b>Day 3–4:</b> backed by {@code PersistentStorageEngine} (WAL — survives crashes).<br>
 * <b>Day 5–6:</b> backed by {@link LsmStorageEngine} (WAL + SkipList memtable + SSTables).<br>
 * <b>Week 2:</b> will add background compaction + leader-follower replication.
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
     * <p>Day 5–6: {@link LsmStorageEngine} — WAL-first writes, SkipList memtable,
     * automatic SSTable flush when memtable exceeds {@code memtableMaxSizeMb}.
     * Crash recovery replays WAL and reloads existing SSTable files on startup.
     */
    @Bean
    public StorageEngine storageEngine(NodeProperties props) {
        Path dataDir = Path.of(props.dataDir());
        long maxBytes = (long) props.memtableMaxSizeMb() * 1024 * 1024;
        log.info("Initializing LsmStorageEngine for node '{}' at '{}' (memtable {}MB)",
                props.id(), dataDir.toAbsolutePath(), props.memtableMaxSizeMb());
        return new LsmStorageEngine(dataDir, maxBytes);
    }
}

