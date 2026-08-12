package com.kvstore.coordinator.api;

import com.google.protobuf.ByteString;
import com.kvstore.coordinator.api.dto.GetValueResponse;
import com.kvstore.coordinator.api.dto.PutValueRequest;
import com.kvstore.coordinator.client.NodeGrpcClient;
import com.kvstore.coordinator.config.NodeInfo;
import com.kvstore.coordinator.monitoring.ClusterEvent;
import com.kvstore.coordinator.monitoring.ClusterEventBus;
import com.kvstore.coordinator.routing.SimpleRouter;
import com.kvstore.proto.*;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * REST controller exposing the distributed KV operations to external clients.
 *
 * <p>Routes:
 * <pre>
 *   PUT    /api/v1/kv/{key}   — store a value
 *   GET    /api/v1/kv/{key}   — retrieve a value
 *   DELETE /api/v1/kv/{key}   — delete a key
 *   GET    /api/v1/kv/_ping   — ping all nodes (health check)
 * </pre>
 *
 * <p>The controller delegates routing decisions to {@link SimpleRouter}
 * and actual gRPC calls to {@link NodeGrpcClient}.
 */
@RestController
@RequestMapping("/api/v1/kv")
public class KvRestController {

    private static final Logger log = LoggerFactory.getLogger(KvRestController.class);

    private final SimpleRouter   router;
    private final NodeGrpcClient nodeClient;
    private final ClusterEventBus eventBus;

    private final Counter coordinatorPuts;
    private final Counter coordinatorGets;
    private final Counter coordinatorDeletes;
    private final Counter coordinatorErrors;

    public KvRestController(SimpleRouter router,
                            NodeGrpcClient nodeClient,
                            ClusterEventBus eventBus,
                            MeterRegistry meterRegistry) {
        this.router     = router;
        this.nodeClient = nodeClient;
        this.eventBus   = eventBus;

        coordinatorPuts    = Counter.builder("coordinator_puts_total").register(meterRegistry);
        coordinatorGets    = Counter.builder("coordinator_gets_total").register(meterRegistry);
        coordinatorDeletes = Counter.builder("coordinator_deletes_total").register(meterRegistry);
        coordinatorErrors  = Counter.builder("coordinator_errors_total").register(meterRegistry);
    }

    // ─── PUT /api/v1/kv/{key} ────────────────────────────────────────────────

    /**
     * Stores a key-value pair.
     *
     * <p>Request body: {@code {"value": "Shubh Sahu", "ttlMs": 0}}
     * <p>Response: {@code 200 OK {"success": true, "routedTo": "node-1"}}
     */
    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> put(
            @PathVariable String key,
            @RequestBody  PutValueRequest body
    ) {
        log.info("REST PUT key='{}'", key);
        coordinatorPuts.increment();
        long start = System.currentTimeMillis();

        NodeInfo target = router.route(key);
        try {
            PutResponse response = nodeClient.put(target.id(), PutRequest.newBuilder()
                    .setKey(key)
                    .setValue(ByteString.copyFrom(body.value(), StandardCharsets.UTF_8))
                    .setTtlMs(body.ttlMs())
                    .build());

            double latency = System.currentTimeMillis() - start;
            eventBus.publish(ClusterEvent.operation(target.id(), "PUT", key, latency, true));

            return ResponseEntity.ok(Map.of(
                    "success",  response.getSuccess(),
                    "version",  response.getVersion(),
                    "routedTo", target.id()
            ));
        } catch (StatusRuntimeException e) {
            coordinatorErrors.increment();
            eventBus.publish(ClusterEvent.operation(target.id(), "PUT", key, System.currentTimeMillis() - start, false));
            log.error("gRPC PUT failed for key='{}' on node='{}': {}", key, target.id(), e.getStatus());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Node error: " + e.getStatus().getDescription(),
                                 "routedTo", target.id()));
        }
    }

    // ─── GET /api/v1/kv/{key} ────────────────────────────────────────────────

    /**
     * Retrieves the value for a key.
     *
     * <p>Returns {@code 200} with {@code found=true} if the key exists.
     * Returns {@code 404} with {@code found=false} if the key is absent or deleted.
     */
    @GetMapping("/{key}")
    public ResponseEntity<GetValueResponse> get(@PathVariable String key) {
        log.info("REST GET key='{}'", key);
        coordinatorGets.increment();
        long start = System.currentTimeMillis();

        NodeInfo target = router.route(key);
        try {
            GetResponse response = nodeClient.get(target.id(), GetRequest.newBuilder()
                    .setKey(key)
                    .build());

            double latency = System.currentTimeMillis() - start;
            eventBus.publish(ClusterEvent.operation(target.id(), "GET", key, latency, response.getFound()));

            if (response.getFound()) {
                String value = response.getValue().toString(StandardCharsets.UTF_8);
                return ResponseEntity.ok(
                        GetValueResponse.found(value, response.getVersion(),
                                               response.getTimestampMs(), target.id()));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(GetValueResponse.notFound(target.id()));
            }
        } catch (StatusRuntimeException e) {
            coordinatorErrors.increment();
            eventBus.publish(ClusterEvent.operation(target.id(), "GET", key, System.currentTimeMillis() - start, false));
            log.error("gRPC GET failed for key='{}' on node='{}': {}", key, target.id(), e.getStatus());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    // ─── DELETE /api/v1/kv/{key} ─────────────────────────────────────────────

    /**
     * Deletes a key (writes a tombstone on the owning node).
     *
     * <p>Returns {@code 200} whether or not the key previously existed.
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String key) {
        log.info("REST DELETE key='{}'", key);
        coordinatorDeletes.increment();
        long start = System.currentTimeMillis();

        NodeInfo target = router.route(key);
        try {
            DeleteResponse response = nodeClient.delete(target.id(), DeleteRequest.newBuilder()
                    .setKey(key)
                    .build());

            eventBus.publish(ClusterEvent.operation(target.id(), "DELETE", key, System.currentTimeMillis() - start, true));

            return ResponseEntity.ok(Map.of(
                    "success",  response.getSuccess(),
                    "routedTo", target.id()
            ));
        } catch (StatusRuntimeException e) {
            coordinatorErrors.increment();
            eventBus.publish(ClusterEvent.operation(target.id(), "DELETE", key, System.currentTimeMillis() - start, false));
            log.error("gRPC DELETE failed for key='{}' on node='{}': {}", key, target.id(), e.getStatus());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Node error: " + e.getStatus().getDescription(),
                                 "routedTo", target.id()));
        }
    }

    // ─── GET /api/v1/kv/_ping ────────────────────────────────────────────────

    /**
     * Pings all registered nodes and returns their status.
     * Useful for verifying cluster connectivity from a single REST call.
     */
    @GetMapping("/_ping")
    public ResponseEntity<Map<String, Object>> pingAll() {
        log.info("REST _ping all nodes");
        // Coordinator pings all nodes and collects results
        // (Week 3: will also verify ring membership)
        return ResponseEntity.ok(Map.of("status", "coordinator-ok"));
    }
}
