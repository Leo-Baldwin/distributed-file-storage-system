# Roadmap

Where this project is going: from a final-year university prototype to a **self-hosted, encrypted, decentralised backup and storage system** that spreads itself across the hosts connected to it.

**The core promise:** if any host is lost, both the data and the system itself can be recovered from the hosts that remain.

The dissertation version is preserved at the tag [`v1.0-dissertation`](https://github.com/Leo-Baldwin/distributed-file-storage-system/tree/v1.0-dissertation).

## Design principles

- **Self-hosted.** It runs on hardware you own. No cloud provider is required, and every host is a peer you control.
- **Encrypted.** Hosts only ever store ciphertext, so a stolen disk or host reveals nothing.
- **Decentralised.** No single host is critical. Copies are restored automatically, and the coordinator's own metadata is replicated across the cluster.
- **Organic.** The cluster continuously monitors itself, then repairs, rebalances and redistributes data as hosts join, leave or fail.
- **Built in the open.** Each phase is its own pull request, with tests added for each new behaviour.

## Status

| Phase | Theme | Status |
|---|---|---|
| 0 | Housekeeping: CI, tags | ✅ Done |
| 1 | Hardening: limits, path safety, configuration, `dfs` CLI | ✅ Done |
| 2 | Chunks named by hash, persistent metadata, snapshots | 🔜 Next |
| 3 | Replication engine and erasure coding | Planned |
| 4 | Encryption and access control | Planned |
| 5 | Decentralised coordinator (recovery, then Raft) | Planned |
| 6 | Backup workflow | Planned |
| 7 | Deployment and joining new hosts | Planned |
| 8 | Dashboard and demo | Planned |
| 9 | Optional: distributed processing, S3 API | Optional |

---

## Phase 2: Chunks named by hash, persistent metadata, snapshots
- **Chunk address = SHA-256 of the stored bytes.** Use the layout `chunks/ab/cd/<hash>`. Nodes verify the hash on upload, and identical chunks are stored only once, so repeat backups only upload what changed.
- **Snapshots:** a backup of a folder is a snapshot made of a timestamp, the source host, and the folder's paths, each mapped to a manifest. A manifest is the size, mode, modified time and ordered chunk hashes of one file.
- **Persistent metadata in SQLite.** Tables: `nodes`, `chunk_locations`, `manifests`, `snapshots`.
- **Inventory:** nodes report the chunks they hold when they register, then send changes with their heartbeats. This means chunk locations can always be rebuilt from the nodes themselves.
- **Placement:** most free space first, copies in different failure domains (hosts), and only a partial set of copies (with a warning) when there are too few domains.
- **Commit check:** a snapshot only counts as complete once every chunk is located on at least one node.

## Phase 3: The replication engine
This builds on the dissertation's replication: writes go to several copies, heartbeats monitor health, and reads fail over. It adds what that version lacked: restoring copies, balancing, and per-path policy.
- **Repair loop:** continuously finds chunks with fewer healthy copies than their target and has nodes copy them to each other directly (`REPLICATE_CHUNK`), so file data never passes through the coordinator.
  - Repairs are prioritised: chunks closest to being lost are repaired first.
  - Repairs are rate-limited so they don't saturate the network.
- **Host loss:** hosts that stay down past a grace period are marked as lost and their copies are recreated elsewhere. A host that comes back has its surplus copies cleaned up.
- **Rebalancing:** when a host joins or the cluster is unevenly filled, chunks gradually move towards balanced usage. The safety rule is copy first, delete after, so the copy count never drops.
- **Per-path replication policy:** for example, `/critical` keeps 4 copies and `/bulk` keeps 2, with a cluster-wide default.
- **Scrubbing:** nodes regularly re-hash stored chunks in the background. Corrupt copies are reported, deleted and repaired from a healthy copy.
- **Visibility:** `dfs status` shows healthy, under-replicated and lost chunk counts and repair progress.
- **Erasure coding (Reed–Solomon):**
  - An alternative redundancy policy per path, for example `rs(6,3)`: data is split into 6 pieces plus 3 parity pieces, spread across 9 hosts.
  - Any 3 hosts can be lost and the data recovered, at 1.5× the storage instead of 3–4× for full copies.
  - The repair loop rebuilds a missing piece from any 6 survivors.
  - The Galois-field arithmetic is implemented in-house and tested against known vectors.
  - Placement keeps every piece of a stripe on a different host.

## Phase 4: Encryption and access control
- **Keys:** a passphrase goes through Argon2id to make a master key, and HKDF derives separate keys from it. Recovery phrase.
- **Chunk encryption:** AES-256-GCM with a deterministic nonce, so deduplication still works. Hosts only ever see ciphertext.
- **Encrypted manifests, snapshots and file names.**
- **Metadata copies in the cluster:** after each snapshot, the metadata is encrypted and stored in the cluster as ordinary replicated chunks, and the root hash is recorded on every node. With just the key, the full catalogue can be rebuilt from any surviving hosts.
- **Machine authentication:** a cluster token or mutual TLS. Unauthenticated peers can't read, write or delete anything.

## Phase 5: Decentralised: no single host is critical
- **Step 1, manual recovery:** `dfs recover` on any surviving host starts a new coordinator. It loads the latest encrypted metadata copy, then rebuilds chunk locations from the nodes' inventories.
- **Step 2, automatic failover:**
  - The coordinator runs on 3 or 5 hosts, with its metadata replicated by **Raft** (leader election plus a replicated log).
  - Clients and nodes follow whichever host is currently the leader.
  - Losing any minority of hosts, including the leader, needs no manual action.

## Phase 6: The backup workflow
- **Commands:**
  - `dfs backup <dir>` (only changed chunks are uploaded)
  - `dfs snapshots`
  - `dfs ls`
  - `dfs restore`
  - `dfs forget --keep-daily 7 --keep-weekly 4 --keep-monthly 6`
  - `dfs gc`
  - `dfs check`
- Downloads use several copies in parallel, for large files.
- Scheduled backups through systemd timers.
- **Whole-host recovery guide:** if a machine is destroyed, install `dfs`, join the cluster with the key, and restore its last snapshot.

## Phase 7: Deployment and joining
- Docker Compose for a local test cluster, and systemd units for real Linux hosts. Tailscale or WireGuard for hosts on other networks.
- **Join from a USB stick:**
  - a self-contained build per platform
  - `dfs invite` prints a single-use code
  - `dfs join <code>` asks for a role (storage, backup source, coordinator candidate) and installs a service that starts on boot
  - no permanent secrets on the stick

## Phase 8: Dashboard and demo
- A read-only web dashboard served by the coordinator, showing:
  - hosts and their roles
  - copy health and repairs
  - storage use
  - snapshots
- **Demo:** unplug a host, including the coordinator leader, and watch failover and self-healing keep every file readable.

## Phase 9: Optional extensions
- **Distributed processing:** hosts report CPU, RAM and GPU, and a small job runner spreads work across them. For AI workloads, integrate existing tools such as llama.cpp RPC or exo.
- **S3-compatible API,** so self-hosted apps can keep their data in the cluster.
