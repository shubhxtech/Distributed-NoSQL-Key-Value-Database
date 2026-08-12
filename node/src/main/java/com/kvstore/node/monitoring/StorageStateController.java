package com.kvstore.node.monitoring;

import com.kvstore.engine.LsmStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.node.config.NodeProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes storage engine internals for the cluster dashboard and NodeStatePoller.
 *
 * <p>{@code GET /api/v1/storage/state} returns:
 * <pre>
 * {
 *   "nodeId":              "node-1",
 *   "status":              "UP",
 *   "role":                "FOLLOWER",
 *   "memtableFillPercent": 42,
 *   "walSizeBytes":        2149000,
 *   "sstableCount":        3
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/storage")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class StorageStateController {

    private final StorageEngine  storageEngine;
    private final NodeProperties nodeProperties;

    public StorageStateController(StorageEngine storageEngine, NodeProperties nodeProperties) {
        this.storageEngine  = storageEngine;
        this.nodeProperties = nodeProperties;
    }

    @GetMapping("/state")
    public Map<String, Object> getState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("nodeId", nodeProperties.id());
        state.put("status", "UP");
        state.put("role",   "FOLLOWER");   // Week 2: real role from Raft

        if (storageEngine instanceof LsmStorageEngine lsm) {
            state.put("memtableFillPercent", lsm.memtableFillPercent());
            state.put("walSizeBytes",        lsm.walSizeBytes());
            state.put("sstableCount",        lsm.sstableCount());
        } else {
            state.put("memtableFillPercent", 0);
            state.put("walSizeBytes",        -1L);
            state.put("sstableCount",        0);
        }

        return state;
    }
}
