# Distributed NoSQL Key-Value Database

[![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.2-brightgreen?logo=spring)](https://spring.io/projects/spring-boot)
[![gRPC](https://img.shields.io/badge/gRPC-1.65-blue?logo=grpc)](https://grpc.io)
[![Raft](https://img.shields.io/badge/Consensus-Raft-orange)](https://raft.github.io)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker)](https://docs.docker.com/compose/)
[![React](https://img.shields.io/badge/Dashboard-React%2FVite-61DAFB?logo=react)](https://vitejs.dev)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

> A distributed key-value store built entirely from scratch in Java — modeled after the internals of **RocksDB**, **Cassandra**, **DynamoDB**, and **etcd**. Every subsystem is hand-rolled: the LSM-tree storage engine, consistent-hash router, Raft consensus state machine, observability pipeline, and a real-time React dashboard.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Modules](#modules)
- [Storage Engine (LSM-Tree)](#storage-engine-lsm-tree)
- [Cluster & Routing](#cluster--routing)
- [Raft Consensus](#raft-consensus)
- [Observability](#observability)
- [Quick Start](#quick-start)
- [API Reference](#api-reference)
- [Configuration](#configuration)
- [Design Decisions](#design-decisions)
- [Project Structure](#project-structure)

---

## Overview

This project is a ground-up implementation of a distributed, replicated key-value database. The goal is to understand how production databases work at the systems level — not by using them, but by building each piece from first principles.

**What is implemented:**

| Layer | Technology | Inspired by |
|---|---|---|
| **Storage** | LSM-Tree with WAL, SkipList Memtable, SSTables, Size-tiered Compaction | RocksDB, Cassandra, LevelDB |
| **Read optimizations** | Bloom Filter (MurmurHash3) + Sparse Index + LRU Cache | RocksDB, BigTable |
| **Durability** | WAL with CRC32 checksums + crash-safe replay | PostgreSQL WAL, LevelDB |
| **Routing** | Consistent Hashing (MD5, 150 VNodes/node) | DynamoDB, Cassandra |
| **Transport** | gRPC + Protocol Buffers on dual ports | Cassandra internal wire protocol |
| **Consensus** | Custom Raft (leader election + quorum writes + persistent metadata) | etcd, CockroachDB |
| **Observability** | Prometheus + Grafana + SSE event stream | Datadog, CloudWatch |
| **Frontend** | React/Vite real-time cluster dashboard with hash ring visualizer | — |

**Consistency Guarantee:** CP (strongly consistent). All writes go through the Raft leader and require a majority quorum (>=2/3 nodes) to commit. The system refuses writes during quorum loss.

---

## Architecture

```
REST Client / UI
       |
       v  HTTP :8080
  +----------------------------------+
  |  Coordinator                     |
  |  (Consistent Hash Router)        |
  +------+---------------------------+
         | gRPC (routes writes to Raft leader)
  +------v------+--------+-----------+
  |  Node-1     |  Node-2|  Node-3   |
  |  :9091      |  :9092 |  :9093    |
  |  Raft:9181  |  :9182 |  :9183   |
  |  --------   |  ------|  ------   |
  |  Memtable   |  Memtable  Memtable|
  |  SSTables   |  SSTables  SSTables|
  |  WAL+CRC32  |  WAL+CRC32  WAL    |
  |  BloomFilter|  BloomFilter  BF   |
  |  LRU Cache  |  LRU Cache  LRU    |
  +------+------+--------+-----------+
         +------ Raft gRPC consensus -+
                       |
           Prometheus (:9090) -> Grafana (:3000)
```

---

## Modules

| Module | Description |
|---|---|
| `proto/` | Protocol Buffer definitions for `KvService` (Put/Get/Delete/Ping) and `RaftService` (RequestVote/AppendEntries) |
| `storage-engine/` | Self-contained LSM-tree library: WAL, Memtable, SSTables, Compaction, Bloom Filter, LRU Cache, TTL |
| `node/` | Runnable storage node: Spring Boot + gRPC server + Raft lifecycle + Micrometer metrics |
| `coordinator/` | REST gateway: consistent-hash routing, leader-aware write routing, SSE event stream |
| `raft/` | Transport-agnostic Raft consensus state machine + gRPC transport layer |
| `ui/` | React/Vite real-time cluster dashboard |
| `config/` | Prometheus scrape config + Grafana dashboard provisioning |

---

## Storage Engine (LSM-Tree)

A complete Log-Structured Merge-Tree following the same fundamental design as RocksDB and LevelDB.

### Write Path

```
put(key, value)
      |
      +---> 1. Append to WAL (sequential write + fsync + CRC32)
      |
      +---> 2. Insert into SkipListMemtable (ConcurrentSkipListMap, sorted, lock-free)
                   |
           (When memtable reaches MEMTABLE_MAX_MB)
                   |
                   v
             3. Flush to SSTable on disk (immutable, sorted)
                   |
                   v
             4. Background Compaction (size-tiered, N files -> 1)
```

**Why sequential writes?** Random disk writes have millisecond latency due to seek time. Sequential appends (WAL + SSTable flushes) are 10-100x faster on both SSDs and HDDs.

### Read Path

```
get(key)
    |
    v
1. LRU Cache -> if hit, return immediately
    |
    v
2. SkipListMemtable -> if found, return (may be a tombstone = deleted)
    |
    v
3. For each SSTable (newest to oldest):
   a. Bloom Filter -> if "definitely not present", skip file entirely
   b. Key range pre-check (firstKey/lastKey) -> if out of range, skip
   c. Binary search sparse index -> find nearest data block offset
   d. Linear scan data block -> find exact key or determine absent
    |
    v
Return value / tombstone / not-found
```

### SSTable File Format

Each `.sst` file is a single sequential write with four sections:

```
+------------------------------------------------------+
|  DATA BLOCK                                          |
|  Sorted: [4B keyLen][key][1B flags][4B valLen][value][8B version]
+------------------------------------------------------+
|  BLOOM FILTER BLOCK                                  |
|  MurmurHash3 bit-array (1% false-positive rate)      |
+------------------------------------------------------+
|  SPARSE INDEX BLOCK                                  |
|  [4B count] then N x [4B keyLen][key][8B dataOffset] |
+------------------------------------------------------+
|  FOOTER (28 bytes, fixed)                            |
|  [8B indexOffset][8B bloomOffset][4B entryCount][8B magic]
+------------------------------------------------------+
```

**Tombstones:** DELETE writes a tombstone (`flags & 0x01`) rather than erasing the key. This is required because older versions may exist in lower SSTables. Tombstones are purged during compaction.

### Compaction

Size-tiered compaction runs in a background thread when SSTable count exceeds threshold. It performs a sorted k-way merge of N SSTables into one, eliminating duplicate keys (keeps highest version) and tombstones. This bounds read amplification and reclaims disk space.

### Crash Safety

On startup, `WalReader` replays the WAL from the last valid record. Each record has a CRC32 checksum. If a truncated or corrupt record is detected (e.g. power failure mid-write), replay stops cleanly at the last verified offset — no partial writes reach the Memtable.

### TTL Support

Keys written with an optional TTL are expired by a background `TtlReaper` thread, which writes tombstones for expired keys before they flush to disk.

---

## Cluster & Routing

### Consistent Hashing

The coordinator routes every key using a **consistent-hash ring** — the same algorithm as DynamoDB and Cassandra.

**Why not `hash(key) % N`?** Modulo hashing remaps every key when nodes join or leave. Consistent hashing bounds remapping to only the keys owned by the affected node.

**How it works:**
1. The 128-bit MD5 hash space is treated as a ring (0 to 2^128 - 1).
2. Each physical node gets **150 virtual node positions** (e.g. `node-1#0` ... `node-1#149`).
3. A key is hashed with MD5 and routed to the **first node clockwise** from its position.
4. Ring is backed by `ConcurrentSkipListMap<BigInteger, NodeInfo>` — O(log N) ceiling lookups, thread-safe.

**Virtual nodes** prevent hot-spots: without VNodes, 3 physical nodes could give one node 60%+ of the keyspace. 150 VNodes ensures <=5% deviation.

### Leader-Aware Write Routing

All writes (PUT, DELETE) are routed to the current Raft leader:
1. Coordinator caches the last known leader ID.
2. Sends the gRPC write directly to the cached leader.
3. If the node responds `NOT_LEADER:<newLeaderId>`, coordinator updates cache and retries on the correct leader.
4. Reads (GET) are routed via consistent hash to any live node.

---

## Raft Consensus

Each storage node runs a `RaftNode` — a complete implementation of the Raft consensus algorithm (Ongaro & Ousterhout, 2014). The state machine is **transport-agnostic**: `GrpcRaftTransport` handles actual network calls. This design makes the state machine fully unit-testable in isolation.

### What is implemented

| Feature | Status |
|---|---|
| Leader election with randomized timeout (150-300ms) | Done |
| Heartbeat (empty AppendEntries every 50ms) | Done |
| RequestVote RPC — term + log-up-to-date check | Done |
| AppendEntries RPC — log consistency check + commit | Done |
| Log replication on majority quorum (N/2 + 1) | Done |
| Raft -> LSM bridge (committed entries applied to storage) | Done |
| gRPC transport on dedicated Raft port | Done |
| Spring Boot lifecycle integration (SmartLifecycle) | Done |
| Persistent state (term + votedFor flushed to `raft.state`) | Done |
| Follower rejects writes with `NOT_LEADER:<leaderId>` | Done |

### Raft Write Flow

```
Client -> Coordinator -> Leader Node -> Followers (2)
  PUT      gRPC Put      appendEntry()  AppendEntries RPC
                         <-- ACK (quorum)
                         commitIndex++
                         LsmEngine.put()
           <-- gRPC OK
<-- 200 OK
```

### Persistent State Guarantees

`currentTerm` and `votedFor` are written to `raft.state` before every state transition. This prevents:
- **Term regression**: A rebooted node cannot decrement its term and issue stale votes.
- **Double voting**: A rebooted node cannot vote for two candidates in the same term.

### Raft Roles

```
[Startup] --> FOLLOWER
                 |
        (election timeout)
                 v
            CANDIDATE --(majority votes)--> LEADER
                 |                              |
        (higher term)                  (higher term)
                 +----------> FOLLOWER <--------+

LEADER: Sends heartbeat every 50ms
FOLLOWER: Resets election timer on each valid heartbeat
CANDIDATE: Transient state during elections
```

Dashboard shows: amber border + crown badge for LEADER, purple badge for CANDIDATE.

---

## Observability

### Live React Dashboard (`localhost:5173`)

Connects to the coordinator SSE stream and updates in real-time without polling.

| Panel | Description |
|---|---|
| **Stats Bar** | Cluster-wide operation counts, p99 latency, live event rate |
| **Hash Ring** | SVG visualization of the MD5 ring with all 450 virtual node positions |
| **Node Cards** | Live memtable fill %, WAL size, SSTable count, LRU cache hit rate, Raft role |
| **Terminal Log** | Streaming event log: operations, SSTable flushes, node status changes |
| **Storage Visualizer** | Deep view of SSTable files and live memtable contents per node |
| **Burst Test** | Fire N concurrent writes and observe key distribution |
| **Write Path** | Animated WAL -> Memtable -> SSTable flow diagram |
| **Interactive Store** | Manual PUT/GET/DELETE from the UI |

### Prometheus Metrics (`localhost:9090`)

Key custom metrics exported per node:

| Metric | Description |
|---|---|
| `kv.raft.term` | Current Raft term |
| `kv.raft.commit.index` | Committed log index |
| `kv.raft.is.leader` | 1 if leader, 0 otherwise |
| `kv.memtable.fill.percent` | Memtable usage % |
| `kv.wal.bytes` | WAL file size in bytes |
| `kv.sstable.count` | Number of SSTable files on disk |
| `coordinator_puts_total` | Total PUT requests through coordinator |
| `coordinator_gets_total` | Total GET requests |
| `coordinator_errors_total` | Total coordinator errors |

Grafana dashboards are auto-provisioned on first startup at `localhost:3000` (admin/admin).

---

## Quick Start

### Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java JDK | 21+ | `brew install openjdk@21` |
| Docker Desktop | Latest | [docker.com](https://docker.com) |
| Node.js | 18+ | `brew install node` |
| grpcurl (optional) | Latest | `brew install grpcurl` |

### Option A — Full cluster with Docker Compose (recommended)

```bash
git clone https://github.com/shubhxtech/Distributed-NoSQL-Key-Value-Database.git
cd Distributed-NoSQL-Key-Value-Database

# Build and start all services
docker compose up --build -d

# Wait ~30s for health checks, then verify
curl http://localhost:8080/api/v1/monitor/state
```

| Service | URL | Description |
|---|---|---|
| **Coordinator REST API** | http://localhost:8080 | PUT / GET / DELETE |
| **Node-1 Actuator** | http://localhost:8081/actuator | Health + metrics |
| **Node-2 Actuator** | http://localhost:8082/actuator | |
| **Node-3 Actuator** | http://localhost:8083/actuator | |
| **Prometheus** | http://localhost:9090 | Metric queries |
| **Grafana** | http://localhost:3000 | Dashboards (admin/admin) |

Then start the dashboard UI:

```bash
cd ui && npm install && npm run dev
# Dashboard: http://localhost:5173
```

### Option B — Run locally without Docker

```bash
./gradlew build -x test

# Terminal 1
NODE_ID=node-1 GRPC_PORT=9091 HTTP_PORT=8081 RAFT_PORT=9181 \
  RAFT_PEERS="node-2=localhost:9182,node-3=localhost:9183" \
  ./gradlew :node:bootRun

# Terminal 2
NODE_ID=node-2 GRPC_PORT=9092 HTTP_PORT=8082 RAFT_PORT=9182 \
  RAFT_PEERS="node-1=localhost:9181,node-3=localhost:9183" \
  ./gradlew :node:bootRun

# Terminal 3
NODE_ID=node-3 GRPC_PORT=9093 HTTP_PORT=8083 RAFT_PORT=9183 \
  RAFT_PEERS="node-1=localhost:9181,node-2=localhost:9182" \
  ./gradlew :node:bootRun

# Terminal 4
./gradlew :coordinator:bootRun

# Terminal 5
cd ui && npm install && npm run dev
```

### Run Tests

```bash
./gradlew test

# Raft unit tests only (covers election, log replication, split-vote, safety)
./gradlew :raft:test
```

---

## API Reference

### PUT

```bash
curl -X PUT http://localhost:8080/api/v1/kv/user:1 \
  -H 'Content-Type: application/json' \
  -d '{"value": "Shubh Sahu"}'

# Response
{"success": true, "routedTo": "node-2", "replicas": ["node-1", "node-3"]}
```

### GET

```bash
curl http://localhost:8080/api/v1/kv/user:1

# Found
{"found": true, "value": "Shubh Sahu", "version": 1724142000000, "routedTo": "node-2"}

# Not found
{"found": false, "routedTo": "node-2"}
```

### DELETE

```bash
curl -X DELETE http://localhost:8080/api/v1/kv/user:1
# {"success": true, "routedTo": "node-2"}
```

### Cluster monitoring

```bash
# Cluster state snapshot
curl http://localhost:8080/api/v1/monitor/state

# Consistent hash ring (450 virtual nodes)
curl http://localhost:8080/api/v1/monitor/ring

# SSE event stream (real-time, keep-alive)
curl -N http://localhost:8080/api/v1/monitor/events

# Per-node storage state (Raft role, memtable %, SSTable count)
curl http://localhost:8081/api/v1/storage/state

# Deep storage dump
curl http://localhost:8081/api/v1/storage/debug/dump

# Trigger manual compaction
curl -X POST http://localhost:8081/api/v1/storage/compact
```

### Fault injection

```bash
# Isolate node-2 at the coordinator level (stops routing to it)
curl -X POST http://localhost:8080/api/v1/monitor/nodes/node-2/kill

# Reconnect node-2
curl -X POST http://localhost:8080/api/v1/monitor/nodes/node-2/restart
```

> **Note:** The isolate endpoint simulates a coordinator-level routing block, not a process kill. The node's Raft process continues running. To simulate a true crash, use `docker stop kv-node-2`.

### Direct gRPC (grpcurl)

```bash
# Ping
grpcurl -plaintext -d '{"sender_id":"cli"}' localhost:9091 kvstore.v1.KvService/Ping

# Put (bypasses coordinator, goes directly to node gRPC port)
grpcurl -plaintext \
  -d '{"key":"hello","value":"'$(echo -n 'world' | base64)'"}' \
  localhost:9091 kvstore.v1.KvService/Put

# Get
grpcurl -plaintext -d '{"key":"hello"}' localhost:9091 kvstore.v1.KvService/Get
```

---

## Configuration

| Variable | Default | Description |
|---|---|---|
| `NODE_ID` | `node-1` | Unique node identifier |
| `HTTP_PORT` | `8081` | HTTP port for actuator + storage API |
| `GRPC_PORT` | `9091` | gRPC port for client KvService RPCs |
| `RAFT_PORT` | `9181` | Dedicated Raft consensus port (isolated from client traffic) |
| `RAFT_PEERS` | _(empty)_ | `"node-2=host:9182,node-3=host:9183"` |
| `DATA_DIR` | `./data/node-1` | Directory for WAL, SSTables, and `raft.state` |
| `MEMTABLE_MAX_MB` | `8` | Flush threshold |

Coordinator cluster membership:

```bash
KV_CLUSTER_NODES_0_ID=node-1
KV_CLUSTER_NODES_0_HOST=localhost
KV_CLUSTER_NODES_0_GRPC_PORT=9091
KV_CLUSTER_NODES_0_HTTP_PORT=8081
```

---

## Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| **Memtable** | `ConcurrentSkipListMap` | Sorted iteration for SSTable flush; lock-free CAS reads; no external library |
| **WAL CRC32** | Per-record checksum | Detects torn writes; replay stops at first corrupt record |
| **SSTable format** | Custom binary (data + bloom + sparse index + footer) | Full layout control; mirrors LevelDB format; zero external dependency |
| **Sparse index** | Every Nth key -> byte offset | Lower memory vs full index; binary search + short linear scan |
| **Compaction** | Size-tiered | Simpler; write-heavy workloads benefit from fewer, larger files |
| **Hash algorithm** | MD5 (128-bit) | Uniformly distributed; faster than SHA for non-security routing |
| **Virtual nodes** | 150 per physical node | <5% load deviation for 3-node cluster; Cassandra uses 256 |
| **Consensus** | Custom Raft state machine | Maximum learning value; transport-agnostic for isolated unit testing |
| **Raft port** | Dedicated gRPC port | Prevents client traffic from starving consensus heartbeats |
| **Raft integration** | `Consumer<RaftLogEntry>` applier | Decouples Raft from storage; Raft stays independently testable |
| **Persistent Raft state** | `raft.state` (term + votedFor) | Prevents term regression and double-voting after restart |
| **Spring lifecycle** | `SmartLifecycle` | Ensures Raft starts after gRPC is ready; shuts down cleanly |
| **Metrics** | Micrometer -> Prometheus -> Grafana | JVM standard; zero-code instrumentation |
| **SSE for dashboard** | Server-Sent Events | One-directional push; no library overhead; browser-native |

### Consistency Model

- **Writes:** Strong consistency — all PUTs and DELETEs commit via Raft quorum.
- **Reads:** All nodes hold replicated committed state via Raft.
- **CAP stance:** **CP** — consistency over availability; cluster refuses writes without quorum.

---

## Project Structure

```
Distributed-NoSQL-Key-Value-Database/
|
+-- proto/
|   +-- src/main/proto/
|       +-- kv.proto                # KvService: Put, Get, Delete, Ping
|       +-- raft.proto              # RaftService: RequestVote, AppendEntries
|
+-- storage-engine/
|   +-- src/main/java/com/kvstore/engine/
|       +-- LsmStorageEngine.java
|       +-- StorageEngine.java      # Interface
|       +-- ValueEntry.java         # Value + timestamp + TTL + tombstone flag
|       +-- wal/
|       |   +-- WalWriter.java      # Append + fsync + CRC32
|       |   +-- WalReader.java      # Crash-safe replay on startup
|       |   +-- WalEntry.java
|       +-- lsm/
|       |   +-- SkipListMemtable.java
|       +-- sstable/
|       |   +-- SSTableWriter.java  # Writes data + bloom + sparse index + footer
|       |   +-- SSTableReader.java  # Bloom pre-filter + binary search + linear scan
|       |   +-- SSTableMetadata.java
|       +-- bloomfilter/
|       |   +-- BloomFilter.java    # MurmurHash3, 1% FPR
|       +-- cache/
|       |   +-- LruCache.java
|       +-- compaction/
|       |   +-- CompactionManager.java  # Background size-tiered k-way merge
|       +-- ttl/
|           +-- TtlReaper.java
|
+-- raft/
|   +-- src/main/java/com/kvstore/raft/
|       +-- RaftNode.java           # State machine: election + replication + commit
|       +-- RaftLog.java            # Thread-safe append-only log
|       +-- RaftLogEntry.java       # term + index + command bytes
|       +-- RaftCommand.java        # Base64 PUT/DELETE command
|       +-- RaftRole.java           # FOLLOWER, CANDIDATE, LEADER
|       +-- RaftTransport.java      # Interface
|       +-- GrpcRaftTransport.java  # gRPC implementation
|       +-- RaftServiceGrpcImpl.java
|
+-- node/
|   +-- src/main/java/com/kvstore/node/
|       +-- NodeApplication.java    # Spring beans + gRPC server lifecycle
|       +-- config/NodeProperties.java
|       +-- grpc/
|       |   +-- KvServiceGrpcImpl.java  # PUT/GET/DELETE; enforces leader check
|       +-- metrics/
|       |   +-- StorageMetrics.java     # Raft term, commit index, is_leader gauges
|       +-- monitoring/
|           +-- StorageStateController.java  # /storage/state + /compact + /debug/dump
|
+-- coordinator/
|   +-- src/main/java/com/kvstore/coordinator/
|       +-- CoordinatorApplication.java
|       +-- api/KvRestController.java        # PUT/GET/DELETE with leader-aware routing
|       +-- routing/ConsistentHashRouter.java
|       +-- replication/ReplicationService.java  # @Deprecated - superseded by Raft
|       +-- client/NodeGrpcClient.java
|       +-- monitoring/
|           +-- ClusterEvent.java
|           +-- ClusterEventBus.java
|           +-- MonitoringController.java    # SSE stream + isolate/reconnect
|           +-- NodeStatePoller.java
|
+-- ui/
|   +-- src/
|       +-- components/
|       |   +-- Dashboard.tsx
|       |   +-- NodeCard.tsx           # Per-node metrics + Raft role
|       |   +-- HashRing.tsx           # SVG ring visualizer
|       |   +-- TerminalLog.tsx        # Live event stream
|       |   +-- StorageVisualizer.tsx  # SSTable + memtable deep view
|       |   +-- BurstTest.tsx          # Load tester
|       |   +-- InteractiveStore.tsx   # Manual CRUD
|       |   +-- StatsBar.tsx
|       |   +-- WritePath.tsx          # Animated write path diagram
|       +-- hooks/
|           +-- useClusterStream.ts    # SSE + node state reducer
|
+-- config/
|   +-- prometheus.yml
|   +-- grafana/provisioning/
|       +-- datasources/
|       +-- dashboards/kv-dashboard.json   # Pre-built Grafana dashboard
|
+-- docker-compose.yml
+-- Dockerfile.node
+-- Dockerfile.coordinator
+-- build.gradle
+-- gradle.properties
+-- settings.gradle
```

---

## License

MIT — see [LICENSE](LICENSE).

---

*Built to understand distributed systems from the ground up, not just use them.*
