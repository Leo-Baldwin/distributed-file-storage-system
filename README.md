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
│                      │         │  - ChunkStore        │
│  - File metadata     │         │  - Disk persistence  │
│  - Node registry     │         │  - Upload/download   │
│  - Heartbeat sweeper │         │    handler           │
│  - Lifecycle states  │         │                      │
│  - Replica placement │         │  Stores NO metadata  │
└──────────────────────┘         └──────────────────────┘
```

**Coordinator Server** — Central control-plane that manages file metadata, tracks registered storage nodes via heartbeats, enforces file lifecycle states (INIT → UPLOADING → COMPLETE), and decides replica placement. Importantly, the Coordinator never handles or stores any file content.

**Node Server(s)** — Data-plane storage units that receive, persist, and serve file chunks on disk. Each Node maintains a background connection to the Coordinator for registration and periodic heartbeats. Nodes are stateless with respect to global system state — they simply store and retrieve chunks.

**Client Applications** — Orchestrate file operations by first contacting the Coordinator for metadata and upload/download plans, then communicating directly with Node servers for chunk transfer. The client handles file chunking, multi-node replication during upload, and file reconstruction during download.

---

## How It Works

### Upload Workflow

1. Client sends `FILES_INIT_REQUEST` to the Coordinator with file metadata (name, size, chunk size).
2. Coordinator creates a file record, selects up to the replication factor's worth of active Nodes, and returns the upload plan including all target Node endpoints.
3. Client splits the file into fixed-size chunks (default 4 MiB).
4. For each chunk, the client uploads to **all** assigned Nodes in parallel via `CHUNK_UPLOAD`, over one reused connection per Node, requiring an `OK` acknowledgement from every target (strict consistency).
5. Client sends `FILES_COMMIT` to the Coordinator, which transitions the file status to `COMPLETE`.

### Download Workflow

1. Client sends `FILES_GET_REQUEST` with the file ID.
2. Coordinator returns file metadata and all available download sources (replica Node endpoints).
3. Client downloads chunks sequentially over one reused connection per source, trying sources in order. If a Node fails, the client skips it and falls back to the next replica transparently.
4. Chunks are written in order to a temporary file, which is moved into place only once the whole file has arrived.

### Node Health Monitoring

- Each Node sends periodic heartbeats to the Coordinator (every 5 seconds).
- A background sweeper thread on the Coordinator marks Nodes as `DOWN` if no heartbeat is received within 15 seconds.
- Only Nodes with `UP` status are selected for new file uploads.
- Each Node keeps a persistent ID in `<dataDir>/node-id`. If the Coordinator restarts and no longer recognises a Node, the Node reconnects and registers again automatically.

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
| `CHUNK_UPLOAD` | Client → Node | Upload a chunk (header + binary body) |
| `CHUNK_UPLOAD_ACK` | Node → Client | Acknowledge chunk persistence |
| `FILES_COMMIT` | Client → Coordinator | Mark file as complete |
| `FILES_COMMIT_ACK` | Coordinator → Client | Confirm commit |
| `FILES_GET_REQUEST` | Client → Coordinator | Request file metadata for download |
| `FILES_GET_RESPONSE` | Coordinator → Client | Return metadata and download sources |
| `CHUNK_DOWNLOAD` | Client → Node | Request a specific chunk |
| `CHUNK_DOWNLOAD_RESPONSE` | Node → Client | Return chunk (header + binary body) |
| `NODE_REGISTER` | Node → Coordinator | Register a storage node |
| `NODE_REGISTER_ACK` | Coordinator → Node | Confirm registration |
| `NODE_HEARTBEAT` | Node → Coordinator | Periodic liveness signal |
| `NODE_HEARTBEAT_ACK` | Coordinator → Node | Acknowledge heartbeat |

---

## Replication

The system implements **client-driven synchronous replication** at the file level:

- When a file upload is initialised, the Coordinator selects up to the replication factor (default: 3) of active Nodes to store replicas.
- The selected Node endpoints are persisted in `FileMetadata.replicaNodes` and returned to the client as `uploadTargets`.
- During upload, the client sends each chunk to **all** targets and requires an OK ACK from every Node before proceeding (strict acknowledgement policy).
- During download, the Coordinator returns all replica Nodes as `downloadSources`. The client tries each source in order, providing transparent failover if a Node is unavailable.
- If fewer Nodes are available than the replication factor, the system degrades gracefully and uses whatever Nodes are available.

The replication protocol is fully backward-compatible. Legacy fields (`uploadHost`/`uploadPort`, `downloadHost`/`downloadPort`) are still populated for single-node configurations. Clients check for the new `uploadTargets`/`downloadSources` fields first and fall back to legacy if absent.

### On-Disk Storage Layout

Each Node stores chunks in a deterministic directory structure:

```
<baseDir>/chunks/<fileId>/<chunkIndex>.bin
```

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
├── config/
│   ├── CoordinatorConfig.java             # Coordinator settings
│   ├── NodeConfig.java                    # Node settings
│   └── ClientConfig.java                  # Client settings
├── coordinator/
│   ├── CoordinatorServer.java             # Central control-plane server
│   ├── CoordinatorConnection.java         # Per-connection protocol handler
│   ├── FileMetadata.java                  # In-memory file metadata record
│   └── NodeInfo.java                      # In-memory node health/status record
├── node/
│   ├── NodeServer.java                    # Data-plane storage server
│   ├── NodeConnection.java                # Per-connection chunk handler
│   └── ChunkStore.java                    # Disk persistence layer
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
│   └── NodeHeartbeatAck.java              # Heartbeat acknowledgement
└── transport/
    ├── TcpMessageReader.java              # Framed message deserialiser
    ├── TcpMessageWriter.java              # Framed message serialiser
    └── ReceivedMessage.java               # Header + body container

src/test/java/com/leo/dfss/
├── client/
│   └── SafeFileNameTest.java              # Server-supplied file names can't choose a path
├── coordinator/
│   └── FileMetadataTest.java              # Unit tests for chunk calculation
├── node/
│   └── ChunkStoreTest.java                # Disk persistence and file ID validation
├── transport/
│   └── TcpMessageReaderTest.java          # Framing and message size limits
└── integration/
    ├── TestCluster.java                   # Starts a Coordinator + N Nodes on free ports
    ├── UploadDownloadRoundTripTest.java   # End-to-end uploads, downloads and failover
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

**1. Start the Coordinator (port 9000):**
```bash
java -jar target/dfs.jar coordinator
```

**2. Start Node Server(s):**
```bash
java -jar target/dfs.jar node --port 9101 --data-dir data/node1
java -jar target/dfs.jar node --port 9102 --data-dir data/node2
java -jar target/dfs.jar node --port 9103 --data-dir data/node3
```

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
- **ChunkStoreTest**: disk read/write round trip, and rejection of file IDs that could escape the data directory.
- **SafeFileNameTest**: a file name sent by the server can't make the client write outside the output directory.
- **FileMetadataTest**: chunk count calculation with ceiling division.
- **UploadDownloadRoundTripTest**: starts a Coordinator and three Nodes on free ports and checks SHA-256 equality after a round trip for a 120 KB file split into many chunks and a 50 MiB file. It also checks that a download succeeds after two of the three Nodes are stopped, and that uploading with no Nodes fails with a clear error.
- **NodeLifecycleTest**: Node IDs survive restarts, Nodes register their real port and failure domain, and Nodes re-register after a Coordinator restart.
- **DfsCliTest**: `dfs put` and `dfs get` end to end, and non-zero exit codes on bad input.

---

## Configuration

Every setting can be passed as a flag or as an environment variable. A flag wins over an environment variable, which wins over the default.

| Setting | Flag | Environment variable | Default |
|---|---|---|---|
| Coordinator listen port | `coordinator --port` | `DFS_PORT` | `9000` |
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

- **In-memory metadata** — File records and node registry are lost on Coordinator restart. No persistent storage layer is implemented.
- **Single Coordinator** — The Coordinator is a single point of failure. If it goes down, new uploads/downloads cannot be initiated (though existing Node data is preserved on disk).
- **No encryption** — Data is transmitted and stored in plaintext. No authentication or access control is implemented.
- **No integrity checks** — Stored chunks aren't checksummed, so silent disk corruption isn't detected.
- **No self-healing** — If a Node is lost, its copies aren't recreated elsewhere.
- **Sequential chunk downloads** — Chunks are downloaded one at a time rather than in parallel across replicas.
- **Static node selection** — Replica placement uses first-available selection rather than load-balanced or capacity-aware placement.

### Roadmap

This project is being developed into a self-hosted, encrypted, decentralised backup and storage system. See [docs/ROADMAP.md](docs/ROADMAP.md) for the phases and their status.

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
