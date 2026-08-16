package com.kvstore.node.grpc;

import com.kvstore.raft.RaftNode;
import com.kvstore.raft.RaftServiceGrpcImpl;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * Registers the Raft gRPC service with the {@code net.devh} gRPC Spring Boot starter.
 *
 * <p>{@link RaftServiceGrpcImpl} lives in the {@code :raft} module (no Spring dependency),
 * so the {@code @GrpcService} annotation is applied here in a thin subclass within
 * the {@code :node} Spring Boot module.
 *
 * <p>This pattern keeps the Raft library transport-agnostic while still allowing
 * Spring's auto-configuration to discover and register the gRPC service.
 */
@GrpcService
public class RaftGrpcService extends RaftServiceGrpcImpl {

    public RaftGrpcService(RaftNode raftNode) {
        super(raftNode);
    }
}
