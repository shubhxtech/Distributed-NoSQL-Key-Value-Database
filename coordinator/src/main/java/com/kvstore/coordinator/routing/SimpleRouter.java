package com.kvstore.coordinator.routing;

import com.kvstore.coordinator.config.NodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Day 1–2 router: simple round-robin across all registered nodes.
 *
 * <p><b>Week 3 replacement:</b> This class will be replaced by
 * {@code ConsistentHashRouter} which maps keys deterministically onto a ring,
 * ensuring a given key always routes to the same node (its "owner").
 *
 * <p>Round-robin is used here purely to exercise the multi-node path during
 * Day 1–2 without any hashing complexity. <em>Do not rely on this for
 * key locality — it is intentionally temporary.</em>
 */
@Component
public class SimpleRouter {

    private static final Logger log = LoggerFactory.getLogger(SimpleRouter.class);

    private List<NodeInfo> nodes;
    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Sets the list of available nodes. Called by {@link com.kvstore.coordinator.CoordinatorApplication}
     * after cluster properties are loaded.
     */
    public void setNodes(List<NodeInfo> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("Router requires at least one node");
        }
        this.nodes = List.copyOf(nodes);
        log.info("SimpleRouter configured with {} node(s): {}", nodes.size(),
                nodes.stream().map(NodeInfo::id).toList());
    }

    /**
     * Selects the target node for a given key.
     *
     * <p>Day 1–2: round-robin (key is ignored).
     * Week 3: MD5(key) mod ring position → deterministic node.
     *
     * @param key The KV key being operated on.
     * @return The {@link NodeInfo} of the node that should handle this key.
     */
    public NodeInfo route(String key) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException("Router has no nodes configured");
        }
        // Round-robin: pick next node in sequence
        int index = Math.abs(counter.getAndIncrement() % nodes.size());
        NodeInfo selected = nodes.get(index);
        log.debug("route(key='{}') → node='{}' [round-robin index={}]", key, selected.id(), index);
        return selected;
    }
}
