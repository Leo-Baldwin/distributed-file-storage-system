package com.leo.dfss.coordinator;

import com.leo.dfss.config.CoordinatorConfig;
import com.leo.dfss.meta.MetadataStore;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Central coordinator (control-plane) server for the distributed file storage system.
 *
 * Responsibilities:
 * - Accepts TCP connections and delegates each connection to a {@link CoordinatorConnection} thread.
 * - Keeps durable metadata (files, their chunk lists, known nodes) in a {@link MetadataStore}.
 * - Keeps an in-memory index of which node holds which chunk ({@link ChunkLocations}), rebuilt
 *   from the nodes' own reports.
 * - Tracks node health with heartbeats and chooses where new data is placed.
 * - Never handles file bytes.
 *
 * Threading model:
 * - One thread per client connection (CoordinatorConnection).
 * - A single scheduled sweeper thread marks nodes DOWN if heartbeats time out.
 */
public class CoordinatorServer {

    /**
     * Upper bound on chunks per file (1.2 TB at the default 4 MiB chunk size), so a file's chunk
     * list always fits in one message body: FILES_GET_RESPONSE needs at most
     * 33 + 2 x {@link #MAX_HOLDERS_LISTED} bytes per chunk, i.e. 13.5 MB of the 16 MiB limit.
     */
    public static final long MAX_CHUNKS_PER_FILE = 300_000;

    /**
     * Most holders listed per chunk in FILES_GET_RESPONSE. A chunk can end up on many nodes over
     * time (dedup across uploads, later repair); a client only needs a few to fail over between.
     */
    public static final int MAX_HOLDERS_LISTED = 6;

    private final CoordinatorConfig config;
    private final MetadataStore store;
    private final ChunkLocations locations = new ChunkLocations();

    // Node registry: nodeId -> NodeInfo (restored from the store at startup, all DOWN until they register)
    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CoordinatorSweeper");
                t.setDaemon(true);
                return t;
            });

    // Global connections registry
    private final CopyOnWriteArrayList<CoordinatorConnection> connections = new CopyOnWriteArrayList<>();

    // Counted down once the server socket is bound (or binding failed)
    private final CountDownLatch ready = new CountDownLatch(1);
    // Counted down once start() has fully stopped and the metadata store is closed
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile ServerSocket serverSocket;
    private volatile Exception startupError;

    private volatile boolean running = true;

    /**
     * @throws IllegalStateException if the metadata database can't be opened
     */
    public CoordinatorServer(CoordinatorConfig config) {
        this.config = config;
        this.store = MetadataStore.open(config.metadataFile());
        for (MetadataStore.StoredNode n : store.loadNodes()) {
            nodes.put(n.nodeId(), NodeInfo.restored(n.nodeId(), n.host(), n.port(), n.capacityBytes(),
                    n.failureDomain(), n.freeBytes(), n.lastSeenEpochMs()));
        }
    }

    public static void main(String[] args) {
        new CoordinatorServer(CoordinatorConfig.defaults()).start();
    }

    /**
     * Starts the Coordinator server accept loop and the node heartbeat sweeper.
     * This method blocks until the server is stopped or an unrecoverable I/O error occurs.
     */
    public void start() {
        System.out.println("Starting CoordinatorServer (metadata " + config.metadataFile().toAbsolutePath()
                + ", " + nodes.size() + " known node(s))");

        try (ServerSocket socket = new ServerSocket(config.port())) {
            serverSocket = socket;
            ready.countDown();
            System.out.println("CoordinatorServer listening on port: " + socket.getLocalPort()
                    + " (replication factor " + config.replicationFactor() + ")");

            startNodeSweeper();

            int nextConnectionId = 1;

            while (running) {
                Socket client = socket.accept();

                // Create a per-connection handler thread (one thread per client socket)
                CoordinatorConnection connection =
                        new CoordinatorConnection(client, nextConnectionId++, this);
                connection.setDaemon(true);

                connections.add(connection);
                connection.start();
            }

        } catch (IOException e) {
            if (ready.getCount() > 0) {
                startupError = e;
                ready.countDown();
            }
            if (running) {
                e.printStackTrace();
            } else {
                System.out.println("CoordinatorServer stopped.");
            }
        } finally {
            sweeper.shutdownNow();
            shutdownAllConnections();
            store.close();
            stopped.countDown();
        }
    }

    /**
     * Waits until the server socket is bound.
     *
     * @throws IllegalStateException if binding failed or did not happen in time
     */
    public void awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        if (!ready.await(timeout, unit)) {
            throw new IllegalStateException("Coordinator did not start within " + timeout + " " + unit);
        }
        if (startupError != null) {
            throw new IllegalStateException("Coordinator failed to start", startupError);
        }
    }

    /** @return the port actually bound (useful when configured with port 0). Call after {@link #awaitReady}. */
    public int getPort() {
        ServerSocket s = serverSocket;
        if (s == null) throw new IllegalStateException("Coordinator is not listening yet");
        return s.getLocalPort();
    }

    /**
     * Stops accepting connections, stops the sweeper, closes all connections and the metadata
     * store. Returns once the store is closed, so the data directory is safe to reuse or delete.
     */
    public void shutdown() {
        running = false;
        if (serverSocket == null) {
            store.close(); // start() never ran, so its finally block won't close the store
            return;
        }
        try {
            serverSocket.close(); // makes start()'s accept() fail, which runs its cleanup
        } catch (IOException ignored) {
        }
        try {
            if (!stopped.await(10, TimeUnit.SECONDS)) {
                System.out.println("Coordinator did not stop within 10 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shuts down all active {@link CoordinatorConnection} threads and clears the connection registry.
     */
    public void shutdownAllConnections() {
        for (CoordinatorConnection connection : connections) {
            connection.shutdown();
        }
        connections.clear();
    }

    /**
     * Starts a periodic sweeper that checks node heartbeats.
     * If a node has not been seen within the configured timeout it is marked DOWN.
     */
    private void startNodeSweeper() {
        sweeper.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();

            for (NodeInfo node : nodes.values()) {
                long age = now - node.getLastSeenEpochMs();

                if (node.getStatus() == NodeInfo.Status.UP && age > config.heartbeatTimeoutMs()) {
                    node.markDown();
                    System.out.println("Node " + node.getNodeId() + " is DOWN");
                }
            }
        }, config.sweepIntervalMs(), config.sweepIntervalMs(), TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ files

    /**
     * The result of starting an upload.
     *
     * @param targets nodes every chunk should be written to (one per failure domain)
     * @param warning non-null when fewer than the replication factor of domains are available
     */
    public record UploadPlan(FileMetadata file, List<NodeInfo> targets, String warning) {
    }

    /**
     * Creates a file record in the UPLOADING state and chooses where its chunks should go.
     *
     * @throws IllegalStateException    if no storage nodes are UP
     * @throws IllegalArgumentException if the file would have too many chunks
     */
    public UploadPlan initFileUpload(String filename, long totalSizeBytes, int chunkSizeBytes) {
        if (FileMetadata.totalChunks(totalSizeBytes, chunkSizeBytes) > MAX_CHUNKS_PER_FILE) {
            throw new IllegalArgumentException("File would have more than " + MAX_CHUNKS_PER_FILE
                    + " chunks; use a larger chunk size");
        }

        List<NodeInfo> targets = selectPlacement(config.replicationFactor());
        if (targets.isEmpty()) {
            throw new IllegalStateException("No active storage nodes available");
        }
        String warning = null;
        if (targets.size() < config.replicationFactor()) {
            warning = "Only " + targets.size() + " failure domain(s) available, so this file gets "
                    + targets.size() + " of " + config.replicationFactor() + " copies";
        }

        FileMetadata file = new FileMetadata(UUID.randomUUID().toString(), filename, totalSizeBytes,
                chunkSizeBytes, FileMetadata.Status.UPLOADING, System.currentTimeMillis());
        store.insertFile(file);

        System.out.println("Upload started: " + file.fileName() + " (" + file.fileId() + ") -> "
                + targets.stream().map(NodeInfo::getNodeId).toList());
        return new UploadPlan(file, targets, warning);
    }

    /**
     * Chooses up to {@code copies} UP nodes for new data:
     * - never two in the same failure domain (copies on one machine fail together),
     * - within a domain, the node with the most free space,
     * - domains ordered by that free space, so new data goes where there is most room.
     */
    List<NodeInfo> selectPlacement(int copies) {
        Map<String, NodeInfo> bestPerDomain = new HashMap<>();
        for (NodeInfo node : nodes.values()) {
            if (node.getStatus() != NodeInfo.Status.UP) {
                continue;
            }
            bestPerDomain.merge(node.getFailureDomain(), node,
                    (a, b) -> a.getFreeBytes() >= b.getFreeBytes() ? a : b);
        }
        List<NodeInfo> candidates = new ArrayList<>(bestPerDomain.values());
        candidates.sort(Comparator.comparingLong(NodeInfo::getFreeBytes).reversed()
                .thenComparing(NodeInfo::getNodeId));
        return candidates.subList(0, Math.min(copies, candidates.size()));
    }

    /** Outcome of a commit attempt. */
    public record CommitOutcome(String status, int missingChunks, String message) {
        public boolean ok() {
            return "OK".equals(status);
        }
    }

    /**
     * Commits a file: checks its chunk list and that every chunk is held by at least one node,
     * then stores the list and marks the file COMPLETE.
     *
     * If some chunks haven't been reported by nodes yet, returns MISSING_CHUNKS so the client can
     * retry shortly (nodes report new chunks within a fraction of a second).
     */
    public CommitOutcome commitFile(String fileId, List<String> chunkHashes) {
        Optional<FileMetadata> file = store.getFile(fileId);
        if (file.isEmpty()) {
            return new CommitOutcome("ERROR", 0, "Unknown fileId: " + fileId);
        }
        if (file.get().status() != FileMetadata.Status.UPLOADING) {
            return new CommitOutcome("ERROR", 0, "File is not uploading: " + fileId);
        }
        if (chunkHashes.size() != file.get().totalChunks()) {
            return new CommitOutcome("ERROR", 0, "Expected " + file.get().totalChunks()
                    + " chunk hashes but got " + chunkHashes.size());
        }

        int missing = locations.countUnlocated(chunkHashes);
        if (missing > 0) {
            return new CommitOutcome("MISSING_CHUNKS", missing, missing + " chunk(s) not yet held by any node");
        }

        if (!store.commitFile(fileId, chunkHashes, System.currentTimeMillis())) {
            return new CommitOutcome("ERROR", 0, "File is not uploading: " + fileId);
        }
        System.out.println("Committed " + file.get().fileName() + " (" + fileId + "), "
                + chunkHashes.size() + " chunk(s)");
        return new CommitOutcome("OK", 0, "File committed");
    }

    public Optional<FileMetadata> getFile(String fileId) {
        return store.getFile(fileId);
    }

    /** @return the committed file's chunk hashes, in order */
    public List<String> getFileChunks(String fileId) {
        return store.getFileChunks(fileId);
    }

    public ChunkLocations getChunkLocations() {
        return locations;
    }

    // ------------------------------------------------------------------ nodes

    /**
     * Handles NODE_REGISTER. Re-registering an existing nodeId (e.g. after a node or Coordinator
     * restart) replaces its record, marks it UP and forgets its chunk list, which the node then
     * re-sends in full.
     *
     * Each registration gets a new session ID that heartbeats and chunk reports must echo. If a
     * second process registers with the same nodeId, the first one's next heartbeat is rejected,
     * so it re-registers and re-sends its inventory instead of silently becoming invisible.
     *
     * @return the new session ID, or null if the fields are invalid
     */
    public synchronized String registerNode(String nodeId, String host, int port, long capacityBytes,
                                            String failureDomain) {
        long now = System.currentTimeMillis();

        if (nodeId == null || nodeId.isBlank()) return null;
        if (host == null || host.isBlank()) return null;
        if (port <= 0) return null;

        NodeInfo previous = nodes.get(nodeId);
        if (previous != null && previous.getStatus() == NodeInfo.Status.UP
                && now - previous.getLastSeenEpochMs() < config.heartbeatTimeoutMs()
                && (!previous.getHost().equals(host) || previous.getPort() != port)) {
            System.out.println("WARNING: node " + nodeId + " registered from " + host + ":" + port
                    + " while still live at " + previous.getHost() + ":" + previous.getPort()
                    + " (duplicate node ID? copied data directory?)");
        }

        String domain = (failureDomain == null || failureDomain.isBlank()) ? nodeId : failureDomain;
        String sessionId = UUID.randomUUID().toString();
        NodeInfo node = new NodeInfo(nodeId, host, port, capacityBytes, domain, sessionId, now);
        locations.clearNode(nodeId);
        nodes.put(nodeId, node);
        persist(node);

        System.out.println("Node " + node.getNodeId() + " registered at " + host + ":" + port
                + " (domain " + domain + ")");
        return sessionId;
    }

    /**
     * Handles NODE_HEARTBEAT.
     *
     * @return true if the node is known and the session is current, else false (the node will
     * then re-register)
     */
    public boolean handleHeartbeat(String nodeId, String sessionId, long timeStampEpochMs, long freeBytes) {
        NodeInfo node = nodes.get(nodeId);
        if (node == null || !node.isSession(sessionId)) {
            return false;
        }
        node.updateHeartbeat(timeStampEpochMs, freeBytes);
        persist(node);
        return true;
    }

    /**
     * Handles NODE_CHUNKS_ADDED: the node now holds these chunks.
     *
     * @return false if the node is unknown or the session is stale (it must register again)
     */
    public boolean chunksAdded(String nodeId, String sessionId, List<String> hashes) {
        NodeInfo node = nodes.get(nodeId);
        if (node == null || !node.isSession(sessionId)) {
            return false;
        }
        locations.add(nodeId, hashes);
        return true;
    }

    private void persist(NodeInfo n) {
        store.upsertNode(new MetadataStore.StoredNode(n.getNodeId(), n.getHost(), n.getPort(),
                n.getFailureDomain(), n.getCapacityBytes(), n.getFreeBytes(), n.getLastSeenEpochMs()));
    }

    /** @return the node registry (nodeId → NodeInfo) */
    public Map<String, NodeInfo> getNodes() {
        return nodes;
    }

    /** @return number of nodes currently marked UP */
    public long countActiveNodes() {
        return nodes.values().stream().filter(n -> n.getStatus() == NodeInfo.Status.UP).count();
    }
}
