package com.kvstore.node;

import com.kvstore.engine.InMemoryStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.node.config.NodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
 * <p>Day 1–2: backed by {@link InMemoryStorageEngine}.
 * Week 2: will swap to {@code LsmStorageEngine}.
 */
@SpringBootApplication
@EnableConfigurationProperties(NodeProperties.class)
public class NodeApplication {

    private static final Logger log = LoggerFactory.getLogger(NodeApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(NodeApplication.class, args);
    }

    /**
     * Exposes the StorageEngine as a Spring bean so gRPC service impl can inject it.
     * Swapping engines in Week 2 only requires changing this single bean definition.
     */
    @Bean
    public StorageEngine storageEngine(NodeProperties props) {
        log.info("Initializing InMemoryStorageEngine for node '{}'", props.id());
        // Week 2: return new LsmStorageEngine(props.dataDir());
        return new InMemoryStorageEngine();
    }
}
