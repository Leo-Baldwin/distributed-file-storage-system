# Distributed File Storage System

A Java-based distributed file storage system built from scratch using raw TCP sockets and a custom binary framing protocol. The system splits files into fixed-size chunks, distributes them across multiple storage nodes with configurable replication, and reconstructs them on download — providing fault tolerance and redundancy without relying on any third-party frameworks.

Developed as a final-year project for **BSc Software Engineering** at Anglia Ruskin University.

---

## Table of Contents

- [Architecture](#architecture)
- [How It Works](#how-it-works)
- [Wire Protocol](#wire-protocol)
- [Replication](#replication)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Running Tests](#running-tests)
- [Configuration](#configuration)
- [Limitations & Future Work](#limitations--future-work)

---

## Architecture

The system follows a **control-plane / data-plane separation** pattern inspired by systems like Hadoop HDFS and the Google File System. Three distinct component types interact over TCP:

```
┌────────────────────────────────────────────────────────────────┐
│                          Client                                │
│    (UploadOrchestratorClient / DownloadOrchestratorClient)     │
└──────────┬──────────────────────────────────┬──────────────────┘
           │  metadata (control-plane)        │  chunks (data-plane)
           ▼                                  ▼
┌──────────────────────┐         ┌──────────────────────┐
│   Coordinator        │         │   Node Server(s)     │
│   Server             │         │                      │
│                      │         │  - Chunks stored by  │
│  - Files + chunk     │         │    SHA-256 hash      │
│    lists (SQLite)    │         │  - Verifies hashes   │
│  - Node registry     │         │    on upload         │
│  - Chunk locations   │◀────────│  - Reports what it   │
│    (from node        │inventory│    holds             │
│    reports)          │         │                      │
│  - Placement         │         │  Stores NO metadata  │
└──────────────────────┘         └──────────────────────┘
```

**Coordinator Server** — Central control-plane that keeps the durable metadata (files, the ordered chunk hashes that make up each file, and known nodes) in SQLite. It tracks node health via heartbeats, enforces the file lifecycle (UPLOADING → COMPLETE) and decides placement. It never handles or stores file content. It also doesn't persist which node holds which chunk: nodes report that themselves, so the index is rebuilt automatically after a restart.

**Node Server(s)** — Data-plane storage units that store and serve chunks, addressed by the SHA-256 of their content. Each Node keeps a background connection to the Coordinator, over which it registers, sends heartbeats, and reports its full chunk inventory followed by each new chunk as it arrives. Nodes know nothing about files: they simply store and return chunks.

**Client Applications** — Orchestrate file operations by first contacting the Coordinator for metadata and upload/download plans, then communicating directly with Node servers for chunk transfer. The client handles file chunking, multi-node replication during upload, and file reconstruction during download.

---

## How It Works

### Upload Workflow

1. Client sends `FILES_INIT_REQUEST` to the Coordinator with the file's name, size and chunk size.
2. Coordinator creates a file record (`UPLOADING`), chooses target Nodes (see [Replication](#replication)) and returns the upload plan.
3. Client splits the file into fixed-size chunks (default 4 MiB) and hashes each one (SHA-256).
4. Each chunk is uploaded with `CHUNK_UPLOAD` to **all** targets in parallel, over one reused connection per Node. Every target must acknowledge (strict consistency). Nodes recompute the hash and reject mismatched data, and a chunk a Node already holds isn't stored twice.
5. Client sends `FILES_COMMIT` with the ordered chunk hashes. The Coordinator checks that every chunk is held by at least one Node, then marks the file `COMPLETE`. If a Node hasn't reported a just-stored chunk yet, the reply is `MISSING_CHUNKS` and the client retries shortly.

### Download Workflow

1. Client sends `FILES_GET_REQUEST` with the file ID.
2. Coordinator returns the file's metadata and, for every chunk, which `UP` Nodes hold it.
3. Client fetches each chunk from one of its holders and **verifies it against its hash**:
   - If a Node is unreachable, it's skipped for the rest of the download.
   - If a Node returns a missing or corrupted chunk, only that copy is skipped, and the next holder is tried.
4. Chunks are written in order to a temporary file, which is moved into place only once the whole file has arrived and its size matches.

### Node Health Monitoring

- Each Node sends periodic heartbeats to the Coordinator (every 5 seconds).
- A background sweeper thread on the Coordinator marks Nodes as `DOWN` if no heartbeat is received within 15 seconds.
- Only Nodes with `UP` status are selected for new file uploads.
- Each Node keeps a persistent ID in `<dataDir>/node-id`. If the Coordinator restarts, it remembers known Nodes as `DOWN` until each one reconnects, registers again and re-sends its chunk inventory.

---

## Wire Protocol

The system uses a custom binary framing protocol over TCP. Every message follows this structure:

```
┌──────────────┬──────────────────────────┬─────────────────────┐
│  4 bytes     │  N bytes                 │  M bytes            │
│  Header Len  │  JSON Header (UTF-8)     │  Binary Body        │
│              │  {type, data, bodyLen}   │  (chunk bytes)      │
└──────────────┴──────────────────────────┴─────────────────────┘
```

The JSON header contains a `type` field (message discriminator), a `data` field (serialised request/response payload), and a `bodyLength` field declaring the size of the optional binary body. This design separates control metadata from raw file data, enabling efficient binary transfer without encoding overhead.

### Protocol Messages

| Message Type | Direction | Purpose |
|---|---|---|
| `FILES_INIT_REQUEST` | Client → Coordinator | Initiate file upload |
| `FILES_INIT_RESPONSE` | Coordinator → Client | Return upload plan with Node targets |
| `CHUNK_UPLOAD` | Client → Node | Store a chunk under its hash (body: chunk bytes) |
| `CHUNK_UPLOAD_ACK` | Node → Client | Acknowledge chunk persistence |
| `FILES_COMMIT` | Client → Coordinator | Commit a file (body: ordered chunk hashes, 32 bytes each) |
| `FILES_COMMIT_ACK` | Coordinator → Client | `OK`, `MISSING_CHUNKS` (retry) or `ERROR` |
| `FILES_GET_REQUEST` | Client → Coordinator | Request a file for download |
| `FILES_GET_RESPONSE` | Coordinator → Client | Metadata, plus per chunk its hash and holders (binary body) |
| `CHUNK_DOWNLOAD` | Client → Node | Request a chunk by hash |
| `CHUNK_DOWNLOAD_RESPONSE` | Node → Client | Return chunk (body: chunk bytes) |
| `NODE_REGISTER` | Node → Coordinator | Register a storage node |
| `NODE_REGISTER_ACK` | Coordinator → Node | Confirm registration |
| `NODE_HEARTBEAT` | Node → Coordinator | Periodic liveness signal |
| `NODE_HEARTBEAT_ACK` | Coordinator → Node | Acknowledge heartbeat |
| `NODE_CHUNKS_ADDED` | Node → Coordinator | Chunks this Node holds (body: hashes); full inventory after registering, then new chunks |
| `NODE_CHUNKS_ACK` | Coordinator → Node | Acknowledge a chunk report |

---

## Replication

The system implements **client-driven synchronous replication**:

- When an upload starts, the Coordinator chooses up to the replication factor (default 3) of `UP` Nodes:
  - **never two in the same failure domain**, because copies on one machine fail together
  - the Node with the most free space within each domain
  - the domains with the most free space first
- If there are fewer domains than the replication factor, the file gets fewer copies and the client prints a warning.
- During upload, the client sends each chunk to **all** targets and requires an OK ACK from every Node before proceeding (strict acknowledgement policy).
- The Coordinator tracks replication **per chunk**, from what Nodes report, not per file. Downloads use any Node holding a chunk and fail over transparently.

### On-Disk Storage Layout

Each Node stores chunks under their SHA-256 hash, two directory levels deep so no single directory grows too large:

```
<dataDir>/node-id
<dataDir>/chunks/ab/cd/abcd…(64 hex characters)
```

Chunks are written to a temporary file and renamed into place, so a crash never leaves a partial chunk under a valid name. The Coordinator keeps its metadata in `<coordinator data dir>/metadata.db`.

---

## Project Structure

```
src/main/java/com/leo/dfss/
├── cli/
│   └── Dfs.java                           # The `dfs` command (coordinator | node | put | get)
├── client/
│   ├── UploadOrchestratorClient.java      # Client-side upload orchestration
│   ├── DownloadOrchestratorClient.java    # Client-side download orchestration
│   └── NodeSession.java                   # One reusable connection to a Node
├── common/
│   └── ChunkHashes.java                   # SHA-256 chunk addresses and binary hash lists
├── config/
│   ├── CoordinatorConfig.java             # Coordinator settings
│   ├── NodeConfig.java                    # Node settings
│   └── ClientConfig.java                  # Client settings
├── coordinator/
│   ├── CoordinatorServer.java             # Central control-plane server
│   ├── CoordinatorConnection.java         # Per-connection protocol handler
│   ├── ChunkLocations.java                # Which node holds which chunk (from node reports)
│   ├── FileMetadata.java                  # File record and lifecycle
│   └── NodeInfo.java                      # Node health/status record
├── meta/
│   └── MetadataStore.java                 # SQLite persistence for files, chunk lists, nodes
├── node/
│   ├── NodeServer.java                    # Data-plane storage server
│   ├── NodeConnection.java                # Per-connection chunk handler
│   └── ChunkStore.java                    # Content-addressed chunk storage on disk
├── protocol/
│   ├── Message.java                       # Wire protocol header envelope
│   ├── FilesInitRequest.java              # Upload initiation request
│   ├── FilesInitResponse.java             # Upload plan response (with targets)
│   ├── FilesCommitRequest.java            # File commit request
│   ├── FilesCommitAck.java                # Commit acknowledgement
│   ├── FilesGetRequest.java               # Download metadata request
│   ├── FilesGetResponse.java              # Download metadata response (with sources)
│   ├── ChunkUploadRequest.java            # Chunk upload header
│   ├── ChunkUploadAck.java                # Chunk upload acknowledgement
│   ├── ChunkDownloadRequest.java          # Chunk download request
│   ├── ChunkDownloadResponse.java         # Chunk download response
│   ├── NodeRegisterRequest.java           # Node registration request
│   ├── NodeRegisterAck.java               # Node registration acknowledgement
│   ├── NodeHeartbeat.java                 # Node heartbeat message
│   ├── NodeHeartbeatAck.java              # Heartbeat acknowledgement
│   ├── NodeChunksAdded.java               # Node chunk inventory report
│   ├── NodeEndpoint.java                  # Where a node can be reached
│   └── ChunkPlacements.java               # Binary per-chunk holder lists
└── transport/
    ├── TcpMessageReader.java              # Framed message deserialiser
    ├── TcpMessageWriter.java              # Framed message serialiser
    └── ReceivedMessage.java               # Header + body container

src/test/java/com/leo/dfss/
├── client/
│   └── SafeFileNameTest.java              # Server-supplied file names can't choose a path
├── common/
│   └── ChunkHashesTest.java               # Hashing (known vector) and hash list packing
├── coordinator/
│   ├── FileMetadataTest.java              # Chunk count calculation
│   └── PlacementTest.java                 # Failure-domain and free-space aware placement
├── meta/
│   └── MetadataStoreTest.java             # Metadata survives reopening; commit-once
├── node/
│   └── ChunkStoreTest.java                # Content addressing, dedup, integrity, path safety
├── protocol/
│   └── ChunkPlacementsTest.java           # Placement encoding
├── transport/
│   └── TcpMessageReaderTest.java          # Framing and message size limits
└── integration/
    ├── TestCluster.java                   # Starts a Coordinator + N Nodes on free ports
    ├── UploadDownloadRoundTripTest.java   # End-to-end uploads, downloads and failover
    ├── ContentAddressingTest.java         # Dedup, corrupted copies, Coordinator restart, empty files
    ├── NodeLifecycleTest.java             # Node IDs, registration and re-registration
    └── DfsCliTest.java                    # The `dfs` command end to end
```

---

## Getting Started

### Prerequisites

- **Java 21** or later
- **Apache Maven** 3.8+

### Build

```bash
mvn package
```

This runs the tests and builds `target/dfs.jar`, a single runnable jar.

### Run

Start each component in its own terminal:

**1. Start the Coordinator (port 9000, metadata in `coordinator-data/`):**
```bash
java -jar target/dfs.jar coordinator
```

**2. Start Node Server(s):**
```bash
java -jar target/dfs.jar node --port 9101 --data-dir data/node1 --failure-domain test1
java -jar target/dfs.jar node --port 9102 --data-dir data/node2 --failure-domain test2
java -jar target/dfs.jar node --port 9103 --data-dir data/node3 --failure-domain test3
```

Copies are never placed twice in the same failure domain, which defaults to the machine's host name. On real hosts, leave it unset. When testing several Nodes on one machine, give each Node its own domain as above, or every file gets a single copy.

For nodes on other machines, add `--coordinator <host>:9000`. If clients should reach a node at a different address than the one it uses to connect to the Coordinator, add `--advertise-host`.

**3. Upload a file** (prints the file ID):
```bash
java -jar target/dfs.jar put path/to/file.txt
```

**4. Download a file:**
```bash
java -jar target/dfs.jar get <fileId> -o output/
```

Run `java -jar target/dfs.jar <command> --help` for all options.

---

## Running Tests

```bash
mvn test
```

The test suite includes:

- **TcpMessageReaderTest**: framing round trip, and rejection of oversized or malformed messages before any large allocation.
- **ChunkHashesTest**, **ChunkPlacementsTest**: hashing against a known test vector, and the binary encodings.
- **ChunkStoreTest**: content-addressed storage, deduplication, rejection of data that doesn't match its hash, and rejection of invalid hashes (path safety).
- **MetadataStoreTest**: files, chunk lists and nodes survive reopening the database; a file can be committed only once.
- **PlacementTest**: never two copies in one failure domain, emptiest nodes first, `DOWN` nodes skipped, and a warning when there are too few domains.
- **SafeFileNameTest**: a file name sent by the server can't make the client write outside the output directory.
- **FileMetadataTest**: chunk count calculation, including empty files.
- **UploadDownloadRoundTripTest**: round trips for a 120 KB file split into many chunks and a 50 MiB file; a download after two of three Nodes are stopped; a clear error with no Nodes.
- **ContentAddressingTest**: identical data is stored once; corrupted copies are detected and a good copy is used; files survive a Coordinator restart (state rebuilt from SQLite plus node reports); empty files round trip.
- **NodeLifecycleTest**: Node IDs survive restarts, Nodes register their real port and failure domain, and Nodes come back `UP` after a Coordinator restart.
- **DfsCliTest**: `dfs put` and `dfs get` end to end, and non-zero exit codes on bad input.

---

## Configuration

Every setting can be passed as a flag or as an environment variable. A flag wins over an environment variable, which wins over the default.

| Setting | Flag | Environment variable | Default |
|---|---|---|---|
| Coordinator listen port | `coordinator --port` | `DFS_PORT` | `9000` |
| Coordinator data directory | `coordinator --data-dir` | `DFS_DATA_DIR` | `coordinator-data` |
| Replication factor | `coordinator --replication-factor` | `DFS_REPLICATION_FACTOR` | `3` |
| Node listen port | `node --port` | `DFS_PORT` | `9100` |
| Node data directory | `node --data-dir` | `DFS_DATA_DIR` | `node-data` |
| Coordinator address | `--coordinator` (node, put, get) | `DFS_COORDINATOR` | `localhost:9000` |
| Address clients use to reach a node | `node --advertise-host` | `DFS_ADVERTISE_HOST` | the address the node connects from |
| Failure domain | `node --failure-domain` | `DFS_FAILURE_DOMAIN` | the machine's host name |
| Upload chunk size | `put --chunk-size-kib` | `DFS_CHUNK_SIZE_KIB` | `4096` (4 MiB) |

Fixed limits:

| Limit | Value |
|---|---|
| Heartbeat interval / timeout / sweep | 5 s / 15 s / 5 s |
| Maximum message header | 64 KiB |
| Maximum message body (and so chunk size) | 16 MiB |

---

## Limitations & Future Work

### Current Limitations

- **Single Coordinator** — The Coordinator's metadata survives restarts, but it lives on one host. If that host's disk is lost, the chunks on the Nodes can't be reassembled into files. Replicating the metadata across hosts is planned (Phases 4–5).
- **No encryption** — Data is transmitted and stored in plaintext. No authentication or access control is implemented.
- **No self-healing** — If a Node is lost, its copies aren't recreated elsewhere. Corruption is caught when a chunk is read, but nothing scans for it in the background yet (Phase 3).
- **Sequential chunk downloads** — Chunks are downloaded one at a time rather than in parallel across replicas.

### Planned / Future Enhancements

- End-to-end encryption (AES-256) for data confidentiality in transit and at rest.
- Persistent metadata storage (e.g., embedded database) for Coordinator durability.
- Parallel chunk downloads across multiple replica Nodes.
- Load-balanced or round-robin replica placement strategy.
- Web-based client interface for file management.

---

## Technology Stack

- **Java 21** — Platform and language
- **TCP Sockets** (`java.net`) — Transport layer communication
- **Gson 2.11** — JSON serialisation/deserialisation
- **picocli 4.7** — Command-line interface
- **Apache Maven** — Build and dependency management
- **JUnit 5** — Unit and integration testing

---

## Author

**Leo Baldwin**
BSc Software Engineering, Anglia Ruskin University  
Module: MOD002691 — Final Project (2025/26)
