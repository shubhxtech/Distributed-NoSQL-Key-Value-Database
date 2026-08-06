# Distributed NoSQL Key-Value Database

[![Build](https://img.shields.io/badge/build-passing-brightgreen)]()
[![Java](https://img.shields.io/badge/Java-21-blue)]()
[![License](https://img.shields.io/badge/license-MIT-green)]()

> A distributed key-value database built from scratch in Java, mirroring the core internals of Redis, Cassandra, RocksDB, and DynamoDB.

---

## Architecture

```
 Client (curl / Java SDK)
        │
        ▼
 ┌─────────────────┐
 │   Coordinator   │  REST API :8080
 │  (Spring Boot)  │  Routes keys via consistent hashing (Week 3)
 └────────┬────────┘
          │ gRPC
    ┌─────┼─────┐
    ▼     ▼     ▼
 Node-1  Node-2  Node-3   ← each owns a shard of the key-space
    │     │     │
    └─────┴─────┘
      WAL + Memtable + SSTables + Compaction + Bloom Filter + LRU Cache
      (Week 1-2 storage engine)
      Raft consensus per shard (Week 4)
```

## Quick Start

### Prerequisites

| Tool         | Version | Install                       |
|-------------|---------|-------------------------------|
| Java         | 21      | `brew install openjdk@21`     |
| Docker Desktop | Latest | [docker.com](https://docker.com) |
| grpcurl      | Latest  | `brew install grpcurl`        |

### Run locally (single node)

```bash
# 1. Generate Gradle wrapper
gradle wrapper --gradle-version=8.9

# 2. Build
./gradlew build

# 3. Start a node
./gradlew :node:bootRun

# 4. Start the coordinator (separate terminal)
./gradlew :coordinator:bootRun
```

### Run the full cluster with Docker

```bash
cd docker
docker-compose up --build
```

| Service     | URL                                         |
|------------|---------------------------------------------|
| REST API    | http://localhost:8080/api/v1/kv/{key}       |
| Prometheus  | http://localhost:9099                        |
| Grafana     | http://localhost:3000                        |
| Node-1 gRPC | localhost:9091                              |

---

## API Reference

### PUT — store a value
```bash
curl -X PUT http://localhost:8080/api/v1/kv/user:1 \
  -H 'Content-Type: application/json' \
  -d '{"value": "Shubh Sahu"}'

# Response
{"success": true, "version": 1, "routedTo": "node-1"}
```

### GET — retrieve a value
```bash
curl http://localhost:8080/api/v1/kv/user:1

# Response (found)
{"found": true, "value": "Shubh Sahu", "version": 1, "timestampMs": 1722935800000, "routedTo": "node-1"}

# Response (not found)
{"found": false, "routedTo": "node-2"}
```

### DELETE — remove a key
```bash
curl -X DELETE http://localhost:8080/api/v1/kv/user:1

# Response
{"success": true, "routedTo": "node-1"}
```

### Direct gRPC (via grpcurl)
```bash
# Ping a node directly
grpcurl -plaintext -d '{"sender_id":"cli"}' localhost:9091 kvstore.v1.KvService/Ping

# Put a key directly on node-1
grpcurl -plaintext \
  -d '{"key":"hello","value":"'$(echo -n 'world' | base64)'"}' \
  localhost:9091 kvstore.v1.KvService/Put
```

---

## Project Structure

```
distributed-kv-store/
├── proto/              # .proto definitions → generated gRPC stubs
├── storage-engine/     # LSM-tree engine (WAL, Memtable, SSTables, Compaction, Cache)
├── node/               # Runnable node process (gRPC server + Spring Boot actuator)
├── coordinator/        # REST API + routing + replication coordination
├── raft/               # Raft consensus (Week 4)
├── client/             # Java client SDK (post Week 3)
├── docker/             # Dockerfiles + docker-compose.yml
├── monitoring/         # Prometheus + Grafana configs
└── docs/               # Architecture docs + ADRs
```

---

## Implementation Progress

| Week | Focus                    | Status        |
|------|--------------------------|---------------|
| 1    | WAL + SSTables + Node    | 🔵 In Progress |
| 2    | Full LSM + Bloom + Cache | ⬜ Pending    |
| 3    | Replication + Hashing    | ⬜ Pending    |
| 4    | Raft + Observability     | ⬜ Pending    |

---

## Design Decisions

| Decision             | Choice                        | Rationale                                              |
|---------------------|-------------------------------|--------------------------------------------------------|
| Memtable             | `ConcurrentSkipListMap`       | Sorted iteration; lock-free concurrent reads           |
| SSTable format       | Custom binary (data+index+bloom+footer) | Full control; no external dependency         |
| Compaction           | Size-tiered                   | Simpler; right for write-heavy workloads               |
| Consistent hashing   | MD5 + 150 virtual nodes       | Even distribution; DynamoDB/Cassandra proven approach  |
| Consensus            | Custom Raft (fallback: Apache Ratis) | Learning value; safety net if time-constrained  |

---

## Consistency Model

**Default:** Leader-based reads (strong consistency).  
**CAP stance:** Consistency over Availability during network partitions — no writes without a quorum.  
**Eventual mode:** Follower reads are available as a configurable flag (with potential staleness).

---

## License

MIT
