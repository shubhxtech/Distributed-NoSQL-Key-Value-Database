package com.kvstore.node.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Node configuration — bound from {@code application.yml} under prefix {@code kv.node}.
 *
 * <p>Example:
 * <pre>
 * kv:
 *   node:
 *     id: node-1
 *     data-dir: /data/kv
 * </pre>
 */
@ConfigurationProperties(prefix = "kv.node")
public record NodeProperties(

        /** Unique node identifier (e.g. "node-1"). Set via NODE_ID env var. */
        String id,

        /**
         * Directory where WAL segments and SSTables are stored.
         * Ignored in Day 1–2 (in-memory engine). Used from Week 1, Day 3+.
         */
        String dataDir
) {
    public NodeProperties {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("kv.node.id must be set");
        }
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = "./data/" + id;
        }
    }
}
