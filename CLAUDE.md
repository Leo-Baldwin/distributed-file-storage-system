# CLAUDE.md

Guidance for Claude Code sessions (and agents) working in this repo.

## What this is

A distributed, replicated file storage system in Java 21, which began as Leo Baldwin's final-year project and is tagged `v1.0-dissertation`. It is being grown into a **self-hosted, encrypted, decentralised backup and storage system**: if any host is lost, the data *and* the system can be recovered from the others.

**`docs/ROADMAP.md` is the plan and the source of truth for scope and phase order.** Update its status table when a phase lands.

## Hard rules

- **Never read, back up or test against the user's personal data.** That means `~/Documents`, `~/Desktop`, `~/Pictures`, Photos, iCloud Drive and anything under `~/Library`. This system is not a backup of Apple or iCloud data. Tests and manual runs use generated data in temporary directories only.
- **Each phase or feature goes on its own branch with a PR.** Don't commit to `main` directly. Don't merge a PR unless the user says to.
- **CI must stay green.** `.github/workflows/ci.yml` runs `mvn -B test` on PRs and on pushes to `main`.
- **Every behaviour change comes with tests.** The project's credibility rests on the test suite.
- AI and distributed processing are optional extensions (Phase 9). Don't let them drive core design.

## Commands

```bash
mvn test                       # all tests (about 5 s)
mvn package                    # tests + target/dfs.jar
java -jar target/dfs.jar --help
java -jar target/dfs.jar coordinator
java -jar target/dfs.jar node --port 9101 --data-dir /tmp/dfs/n1
java -jar target/dfs.jar put FILE
java -jar target/dfs.jar get FILE_ID -o DIR
```

For a manual multi-process run, use non-default ports (for example 19000 and 1910x) and a `mktemp -d` directory, and kill the processes afterwards.

## Architecture

- **Coordinator** (`coordinator/`): the control plane. Holds file metadata and the node registry, runs the heartbeat sweeper and chooses replica placement. It never handles file bytes. One thread per connection (`CoordinatorConnection`), which dispatches on message type.
- **Node** (`node/`): the data plane. `ChunkStore` writes chunks to disk. `NodeServer` registers with the coordinator and sends heartbeats, reconnecting and re-registering if the connection is lost. Its persistent ID is in `<dataDir>/node-id`.
- **Client** (`client/`): `UploadOrchestratorClient` and `DownloadOrchestratorClient`. `NodeSession` is one reusable connection to a node. Uploads write to all replicas in parallel; downloads fail over between replicas.
- **Protocol** (`protocol/`, `transport/`): a 4-byte header length, then a JSON header (`Message`: type, data, bodyLength), then an optional binary body. Limits: 64 KiB header and 16 MiB body (`TcpMessageReader`).
- **Config** (`config/`): records `CoordinatorConfig`, `NodeConfig` and `ClientConfig`. No hard-coded hosts or ports elsewhere.
- **CLI** (`cli/Dfs.java`): picocli. Every option has a `DFS_*` environment variable, and flags win over environment variables, which win over defaults.

## Conventions

- **New protocol messages:** add a POJO in `protocol/`, send it as `new Message("TYPE", gson.toJson(pojo))` with an optional body, add a `case` to the receiving connection's switch, and reply with an `ERROR` message (never an uncaught exception) on bad input.
- **Validate untrusted input at the edge.** Anything from the network is untrusted: IDs, file names, lengths. File IDs must be lowercase UUIDs; file names must be plain names with no separators.
- **Output:** client progress goes to stderr, and stdout carries results only (for example, file IDs), so `dfs` can be scripted. Failures exit non-zero.
- **Integration tests use `TestCluster`** (`src/test/.../integration/`). It starts a coordinator and N nodes in-process on port 0, each node with its own failure domain, and waits for registration. Don't use fixed ports or `Thread.sleep` to wait for startup.
- Keep the existing code style: Javadoc on public classes and methods, comments that explain *why*.
