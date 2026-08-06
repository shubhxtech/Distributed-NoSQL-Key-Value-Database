package com.kvstore.coordinator;

import com.kvstore.coordinator.client.NodeGrpcClient;
import com.kvstore.coordinator.config.ClusterProperties;
import com.kvstore.coordinator.routing.SimpleRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the KV coordinator service.
 *
 * <p>The coordinator is a stateless routing layer — it does not store data.
 * Its responsibilities:
 * <ol>
 *   <li>Expose a REST API for clients ({@link com.kvstore.coordinator.api.KvRestController}).</li>
 *   <li>Resolve which node owns a key (currently round-robin; Week 3: consistent hashing).</li>
 *   <li>Forward gRPC calls to the resolved node and relay the response.</li>
 * </ol>
 */
@SpringBootApplication
@EnableConfigurationProperties(ClusterProperties.class)
public class CoordinatorApplication {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CoordinatorApplication.class, args);
    }

    /**
     * Registers all cluster nodes with the gRPC client and the router at startup.
     * Uses a Spring {@code @Bean} method so initialization happens after all
     * properties are bound and before the HTTP server starts accepting requests.
     */
    @Bean
    public NodeGrpcClient nodeGrpcClient(ClusterProperties clusterProperties,
                                          SimpleRouter router) {
        var nodes = clusterProperties.nodeInfoList();
        log.info("Coordinator starting with {} node(s):", nodes.size());
        nodes.forEach(n -> log.info("  → {} at {}:{}", n.id(), n.host(), n.grpcPort()));

        NodeGrpcClient client = new NodeGrpcClient();
        client.registerNodes(nodes);

        router.setNodes(nodes);
        return client;
    }
}
