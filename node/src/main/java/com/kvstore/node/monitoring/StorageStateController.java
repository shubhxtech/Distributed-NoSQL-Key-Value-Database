package com.kvstore.node.monitoring;

import com.kvstore.engine.LsmStorageEngine;
import com.kvstore.engine.StorageEngine;
import com.kvstore.node.config.NodeProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes storage engine internals for the cluster dashboard and NodeStatePoller.
 *
 * <p>{@code GET /api/v1/storage/state} returns live metrics (memtable fill %, WAL size, SSTable count).
 * <p>{@code POST /api/v1/storage/compact} triggers a background compaction cycle on this node.
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

    /**
     * Manually triggers a compaction cycle on this node.
     * Called from the dashboard "Trigger Compaction" button.
     */
    @PostMapping("/compact")
    public ResponseEntity<Map<String, Object>> compact() {
        if (storageEngine instanceof LsmStorageEngine lsm) {
            lsm.triggerCompaction();
            return ResponseEntity.ok(Map.of(
                    "nodeId", nodeProperties.id(),
                    "status", "compaction_triggered"));
        }
        return ResponseEntity.ok(Map.of("status", "not_supported"));
    }
}
