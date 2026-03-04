# Distributed File Storage System

A Java-based distributed file storage system built from scratch using raw TCP sockets and a custom binary framing protocol. The system splits files into fixed-size chunks, distributes them across multiple storage nodes with configurable replication, and reconstructs them on download — providing fault tolerance and redundancy without relying on any third-party frameworks.

Developed as a final-year project for **BEng Software Engineering** at Anglia Ruskin University.

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
- [References](#references)

---

## Architecture

The system follows a **control-plane / data-plane separation** pattern inspired by systems like Hadoop HDFS and the Google File System. Three distinct component types interact over TCP:

```
┌────────────────────────────────────────────────────────────────┐
│                          Client                                │
│          (UploadOrchestratorClient / DownloadOrchestratorClient)│
└──────────┬──────────────────────────────────┬──────────────────┘
           │  metadata (control-plane)        │  chunks (data-plane)
           ▼                                  ▼
┌─────────────────────┐          ┌──────────────────────┐
│   Coordinator        │          │   Node Server(s)     │
│   Server             │          │                      │
│                      │          │  - ChunkStore        │
│  - File metadata     │          │  - Disk persistence  │
│  - Node registry     │          │  - Upload/download   │
│  - Heartbeat sweeper │          │    handler           │
│  - Lifecycle states  │          │                      │
│  - Replica placement │          │  Stores NO metadata  │
└─────────────────────┘          └──────────────────────┘
```

**Coordinator Server** — Central control-plane that manages file metadata, tracks registered storage nodes via heartbeats, enforces file lifecycle states (INIT → UPLOADING → COMPLETE), and decides replica placement. Importantly, the Coordinator never handles or stores any file content.

**Node Server(s)** — Data-plane storage units that receive, persist, and serve file chunks on disk. Each Node maintains a background connection to the Coordinator for registration and periodic heartbeats. Nodes are stateless with respect to global system state — they simply store and retrieve chunks.

**Client Applications** — Orchestrate file operations by first contacting the Coordinator for metadata and upload/download plans, then communicating directly with Node servers for chunk transfer. The client handles file chunking, multi-node replication during upload, and file reconstruction during download.

---

## How It Works

### Upload Workflow

1. Client sends `FILES_INIT_REQUEST` to the Coordinator with file metadata (name, size, chunk size).
2. Coordinator creates a file record, selects up to `REPLICATION_FACTOR` active Nodes, and returns the upload plan including all target Node endpoints.
3. Client splits the file into fixed-size chunks (default 4 KB).
4. For each chunk, the client uploads to **all** assigned Nodes via `CHUNK_UPLOAD`, requiring an `OK` acknowledgement from every target (strict consistency).
5. Client sends `FILES_COMMIT` to the Coordinator, which transitions the file status to `COMPLETE`.

### Download Workflow

1. Client sends `FILES_GET_REQUEST` with the file ID.
2. Coordinator returns file metadata and all available download sources (replica Node endpoints).
3. Client downloads chunks sequentially, trying each source in order. If a Node is unavailable, the client falls back to the next replica transparently.
4. Chunks are written in order to reconstruct the original file.

### Node Health Monitoring

- Each Node sends periodic heartbeats to the Coordinator (every 5 seconds).
- A background sweeper thread on the Coordinator marks Nodes as `DOWN` if no heartbeat is received within 15 seconds.
- Only Nodes with `UP` status are selected for new file uploads.

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

- When a file upload is initialised, the Coordinator selects up to `REPLICATION_FACTOR` (default: 2) active Nodes to store replicas.
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
├── client/
│   ├── UploadOrchestratorClient.java      # Client-side upload orchestration
│   └── DownloadOrchestratorClient.java    # Client-side download orchestration
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
├── coordinator/
│   └── FileMetadataTest.java              # Unit tests for chunk calculation
├── node/
│   └── ChunkStoreTest.java               # Unit tests for disk persistence
└── integration/
    └── UploadDownloadRoundTripTest.java   # Full end-to-end integration test
```

---

## Getting Started

### Prerequisites

- **Java 21** or later
- **Apache Maven** 3.8+

### Build

```bash
mvn clean compile
```

### Run

Start the components in separate terminal windows:

**1. Start the Coordinator (port 9000):**
```bash
mvn exec:java -Dexec.mainClass="com.leo.dfss.coordinator.CoordinatorServer"
```

**2. Start Node Server(s):**
```bash
# Node 1 on port 9100
mvn exec:java -Dexec.mainClass="com.leo.dfss.node.NodeServer"
```

To run multiple Nodes for replication, modify the port and data directory in `NodeServer.main()` or pass arguments (e.g., run Node 2 on port 9200 with a separate data directory).

**3. Upload a file:**
```bash
mvn exec:java -Dexec.mainClass="com.leo.dfss.client.UploadOrchestratorClient" -Dexec.args="path/to/file.txt"
```

**4. Download a file:**
```bash
mvn exec:java -Dexec.mainClass="com.leo.dfss.client.DownloadOrchestratorClient" -Dexec.args="<fileId> output/"
```

---

## Running Tests

```bash
mvn test
```

The test suite includes:

- **FileMetadataTest** — Validates chunk count calculation with ceiling division.
- **ChunkStoreTest** — Verifies disk read/write round-trip and input validation.
- **UploadDownloadRoundTripTest** — Full integration test that starts a Coordinator and three Node servers, uploads a 120 KB file, downloads it, and verifies the SHA-256 hash matches the original.

---

## Configuration

Key parameters are currently defined as constants (prototype scope):

| Parameter | Value | Location |
|---|---|---|
| Coordinator port | `9000` | `CoordinatorServer`, client classes |
| Default chunk size | `4096` bytes (4 KB) | `UploadOrchestratorClient` |
| Replication factor | `2` | `CoordinatorServer.REPLICATION_FACTOR` |
| Heartbeat interval | `5000` ms | `NodeServer` |
| Heartbeat timeout | `15000` ms | `CoordinatorServer.HEARTBEAT_TIMEOUT_MS` |
| Sweeper interval | `5000` ms | `CoordinatorServer.SWEEP_INTERVAL_MS` |

---

## Limitations & Future Work

### Current Limitations

- **In-memory metadata** — File records and node registry are lost on Coordinator restart. No persistent storage layer is implemented.
- **Single Coordinator** — The Coordinator is a single point of failure. If it goes down, new uploads/downloads cannot be initiated (though existing Node data is preserved on disk).
- **No encryption** — Data is transmitted and stored in plaintext. No authentication or access control is implemented.
- **Sequential chunk downloads** — Chunks are downloaded one at a time from a single source rather than in parallel across replicas.
- **Static node selection** — Replica placement uses first-available selection rather than load-balanced or capacity-aware placement.

### Planned / Future Enhancements

- End-to-end encryption (AES-256) for data confidentiality in transit and at rest.
- Persistent metadata storage (e.g., embedded database) for Coordinator durability.
- Parallel chunk downloads across multiple replica Nodes.
- Load-balanced or round-robin replica placement strategy.
- Web-based client interface for file management.
- Support for larger-scale deployments with configurable host/port via CLI arguments or config files.

---

## Technology Stack

- **Java 21** — Platform and language
- **TCP Sockets** (`java.net`) — Transport layer communication
- **Gson 2.11** — JSON serialisation/deserialisation
- **Apache Maven** — Build and dependency management
- **JUnit 5** — Unit and integration testing

---

## Author

**Leo Baldwin**
BEng Software Engineering, Anglia Ruskin University  
Module: MOD002691 — Final Project (2025/26)
