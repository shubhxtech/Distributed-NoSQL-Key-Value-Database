# Architecture Overview

## System Components

### Coordinator
- **Role:** Stateless routing layer. Does NOT store data.
- **Responsibilities:** Hash key → resolve owning node → forward gRPC → relay response.
- **Day 1-2:** Round-robin routing. Week 3: consistent hashing ring.
- **Port:** REST :8080, Prometheus metrics at `/actuator/prometheus`.

### Storage Node
- **Role:** Owns a slice of the key space. Stores data durably.
- **Engine evolution:**
  - Day 1-2: `InMemoryStorageEngine` (ConcurrentHashMap)
  - Week 1 Day 3-7: WAL + SSTable
  - Week 2: Full LSM-tree (Memtable + flush + compaction + bloom filter + LRU cache)
- **Ports:** gRPC :9090, HTTP actuator :8080.

### Raft (Week 4)
- **Role:** Leader election and log replication per shard replica set.
- **States:** FOLLOWER → CANDIDATE → LEADER
- **Heartbeat:** 50ms leader → follower keepalive.
- **Election timeout:** 150–300ms randomized.

---

## Data Flow

### PUT user:1 "Shubh"
```
Client
  → PUT /api/v1/kv/user:1 (REST)
  → Coordinator hashes key → routes to owning node (Week 3+)
  → gRPC Put RPC to leader node
  → Node: WAL append → Memtable insert (Week 1+)
  → Node: replicate to followers (Week 3+), wait for quorum ack
  → Return PutResponse(success=true, version=N)
```

### GET user:1
```
Client
  → GET /api/v1/kv/user:1 (REST)
  → Coordinator hashes key → routes to owning node
  → gRPC Get RPC to node
  → Node read path: LRU cache → Memtable → Bloom filter → SSTable binary search
  → Return GetResponse(found=true, value=..., version=N)
```

---

## Storage Engine (LSM-Tree) — Week 2

```
Write path:
  PUT key=k, value=v
    → WAL append (crash durability)
    → Memtable insert (ConcurrentSkipListMap)
    → IF memtable size > threshold:
        → Freeze memtable
        → Flush to immutable SSTable file (sorted)
        → Truncate WAL segment

Read path:
  GET key=k
    → LRU cache hit? → return immediately
    → Memtable check
    → For each SSTable (newest first):
        → Bloom filter: "definitely not present"? → skip file
        → Index block: binary search for key offset
        → Data block: read and return value
```

---

## SSTable File Format

```
┌────────────────────────────────────────┐
│  DATA BLOCK                            │
│  [key_len(4)][key][val_len(4)][value]  │  sorted by key
│  ...                                   │
├────────────────────────────────────────┤
│  INDEX BLOCK (sparse)                  │
│  [key][offset_into_data_block(8)]      │  one entry per N data entries
│  ...                                   │
├────────────────────────────────────────┤
│  BLOOM FILTER                          │
│  [BitSet bytes] (1% false positive)    │
├────────────────────────────────────────┤
│  FOOTER                                │
│  index_offset(8) bloom_offset(8)       │
│  magic(8) = 0xKVSTORE1                 │
└────────────────────────────────────────┘
```

---

## Raft State Machine (Week 4)

```
                  timeout / no leader
FOLLOWER ──────────────────────────────► CANDIDATE
   ▲                                         │
   │ discovers higher term                   │ receives majority votes
   │ or leader                               ▼
   └────────────────────────────────────── LEADER
                                             │
                                             │ sends heartbeats every 50ms
                                             │ replicates log entries
                                             ▼
                                          FOLLOWER(s)
```

---

## Technology Stack

| Layer       | Technology             |
|------------|------------------------|
| Language   | Java 21                |
| Build      | Gradle 8.9 (multi-module) |
| API        | Spring Boot 3.3 + gRPC |
| Serialization | Protocol Buffers    |
| Storage    | Custom LSM engine      |
| Consensus  | Custom Raft (fallback: Apache Ratis) |
| Metrics    | Micrometer + Prometheus |
| Dashboards | Grafana                |
| Deployment | Docker Compose         |
| Testing    | JUnit 5 + Testcontainers |
