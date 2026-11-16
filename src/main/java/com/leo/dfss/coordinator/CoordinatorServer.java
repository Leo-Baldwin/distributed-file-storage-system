package com.leo.dfss.coordinator;

import com.leo.dfss.config.CoordinatorConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Central coordinator (control-plane) server for the distributed file storage system.
 *
 * Responsibilities:
 * - Accepts TCP connections and delegates each connection to a {@link CoordinatorConnection} thread.
 * - Maintains in-memory file metadata (no file bytes are stored here).
 * - Maintains an in-memory registry of active storage nodes and their heartbeat status.
 *
 * Threading model:
 * - One thread per client connection (CoordinatorConnection).
 * - A single scheduled sweeper thread marks nodes DOWN if heartbeats time out.
 */
public class CoordinatorServer {

    private final CoordinatorConfig config;

    // Global file registry: fileId -> FileMetadata
    private final Map<String, FileMetadata> files = new ConcurrentHashMap<>();

    // Global node registry: nodeId -> NodeInfo
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
    private volatile ServerSocket serverSocket;
    private volatile Exception startupError;

    private volatile boolean running = true;

    public CoordinatorServer(CoordinatorConfig config) {
        this.config = config;
    }

    /** Convenience constructor using default settings on the given port. */
    public CoordinatorServer(int port) {
        this(CoordinatorConfig.onPort(port));
    }

    public static void main(String[] args) {
        new CoordinatorServer(CoordinatorConfig.defaults()).start();
    }

    /**
     * Starts the Coordinator server accept loop and the node heartbeat sweeper.
     * This method blocks until the server is stopped or an unrecoverable I/O error occurs.
     */
    public void start() {
        System.out.println("Starting CoordinatorServer...");

        try (ServerSocket socket = new ServerSocket(config.port())) {
            serverSocket = socket;
            ready.countDown();
            System.out.println("CoordinatorServer listening on port: " + socket.getLocalPort()
                    + " (replication factor " + config.replicationFactor() + ")");

            startNodeSweeper();

            int nextConnectionId = 1;

            while (running) {
                // Blocks until a client connects
                Socket client = socket.accept();
                System.out.println("Accepted connection from " + client.getRemoteSocketAddress());

                // Create a per-connection handler thread (one thread per client socket)
                CoordinatorConnection connection =
                        new CoordinatorConnection(client, nextConnectionId++, this);
                connection.setDaemon(true);

                // Track and start the connection handler thread
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

    /** Stops accepting connections, stops the sweeper and closes all open connections. */
    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        sweeper.shutdownNow();
        shutdownAllConnections();
    }

    /**
     * Shuts down all active {@link CoordinatorConnection} threads and clears the in-memory connection registry.
     */
    public void shutdownAllConnections() {
        System.out.println("Shutting down all connections...");
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

    /**
     * Creates a new in-memory file metadata record and assigns it to active Nodes.
     *
     * @param filename       name of the file
     * @param totalSizeBytes total size of the file
     * @param chunkSizeBytes size of each chunk
     * @return the metadata of the file
     * @throws IllegalStateException if no storage nodes are UP
     */
    public FileMetadata initFileUpload(String filename,
                                       long totalSizeBytes,
                                       int chunkSizeBytes) {

        List<NodeInfo> replicationNodes = getActiveNodesForReplication();
        if (replicationNodes.isEmpty()) {
            throw new IllegalStateException("No active storage nodes available");
        }

        String fileId = UUID.randomUUID().toString();

        FileMetadata metadata =
                new FileMetadata(fileId, filename, totalSizeBytes, chunkSizeBytes);

        metadata.setStatus(FileMetadata.Status.UPLOADING);

        // Store selected replica endpoints in metadata (file-level replication)
        List<FileMetadata.NodeEndpoint> replicaNodes = new ArrayList<>();
        for (NodeInfo node : replicationNodes) {
            replicaNodes.add(new FileMetadata.NodeEndpoint(node.getNodeId(), node.getHost(), node.getPort()));
        }
        metadata.setReplicaNodes(replicaNodes);

        // Backward compatibility: keep legacy single-node fields populated with the first replica
        FileMetadata.NodeEndpoint primary = replicaNodes.get(0);
        metadata.setStorageHost(primary.getHost());
        metadata.setStoragePort(primary.getPort());

        files.put(fileId, metadata);

        System.out.println("New file record created: " + metadata);
        return metadata;
    }

    /**
     * Returns up to the configured replication factor of active nodes for storing replicas.
     * If fewer nodes are available than the replication factor,
     * the method degrades gracefully and returns the available nodes.
     * (Phase 2 replaces this with capacity- and failure-domain-aware placement.)
     */
    public List<NodeInfo> getActiveNodesForReplication() {

        List<NodeInfo> result = new ArrayList<>();

        for (NodeInfo node : nodes.values()) {
            if (node.getStatus() == NodeInfo.Status.UP) {
                result.add(node);

                if (result.size() == config.replicationFactor()) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Handles FILES_COMMIT by marking the file metadata record as COMPLETE.
     *
     * @param fileId identifier for the file being committed
     * @return true if successfully committed, else false.
     */
    public boolean commitFile(String fileId) {

        FileMetadata metadata = files.get(fileId);
        if (metadata == null) {
            return false;
        }

        // Only an in-progress upload can be committed (not an unknown, INIT or already-COMPLETE file).
        // Synchronised on the record so two concurrent commits can't both succeed.
        synchronized (metadata) {
            if (metadata.getStatus() != FileMetadata.Status.UPLOADING) {
                return false;
            }
            metadata.setStatus(FileMetadata.Status.COMPLETE);
        }

        System.out.println("Committed file record: " + metadata);
        return true;
    }

    /**
     * Handles NODE_REGISTER. Re-registering an existing nodeId (e.g. after a node restart)
     * replaces its record and marks it UP.
     *
     * @param nodeId        nodes unique identifier
     * @param host          address clients use to reach the node
     * @param port          servers data port
     * @param capacityBytes the nodes storage capacity in bytes
     * @param failureDomain what the node fails together with (usually its machine)
     * @return true if node registered with CoordinatorServer successfully
     */
    public boolean registerNode(String nodeId, String host, int port, long capacityBytes, String failureDomain) {
        long now = System.currentTimeMillis();

        if (nodeId == null || nodeId.isBlank()) return false;
        if (host == null || host.isBlank()) return false;
        if (port <= 0) return false;

        String domain = (failureDomain == null || failureDomain.isBlank()) ? nodeId : failureDomain;
        NodeInfo node = new NodeInfo(nodeId, host, port, capacityBytes, domain, now);
        nodes.put(nodeId, node);

        System.out.println("Node " + node.getNodeId() + " is registered at " + host + ":" + port
                + " (domain " + domain + ")");
        return true;
    }

    /**
     * Handles NODE_HEARTBEAT.
     *
     * @param nodeId           unique identifier for the node that sent the heartbeat message
     * @param timeStampEpochMs the timestamp that the heartbeat was sent by the node
     * @param freeBytes        free space reported by the node (ignored if not positive)
     * @return true if heartbeat is successfully updated for that nodes NodeInfo registry, else false
     */
    public boolean handleHeartbeat(String nodeId, long timeStampEpochMs, long freeBytes) {
        NodeInfo node = nodes.get(nodeId); // Retrieve node by its ID
        if (node == null) {
            return false; // unknown node (e.g. the Coordinator restarted); the node will re-register
        }

        node.updateHeartbeat(timeStampEpochMs, freeBytes);
        return true;
    }

    /**
     * Returns the first Node in the registry that is currently marked {@code UP}.
     */
    public NodeInfo getAnyActiveNode() {
        for (NodeInfo node : nodes.values()) {
            if (node.getStatus() == NodeInfo.Status.UP) return node;
        }
        return null;
    }

    /**
     * Returns the current in-memory node registry.
     *
     * @return map of nodeId to {@link NodeInfo}
     */
    public Map<String, NodeInfo> getNodes() {
        return nodes;
    }

    /** @return number of nodes currently marked UP */
    public long countActiveNodes() {
        return nodes.values().stream().filter(n -> n.getStatus() == NodeInfo.Status.UP).count();
    }

    /**
     * Method to lookup metadata of file by fileId.
     *
     * @param fileId identifier of file being retrieved
     * @return the metadata of specified file
     */
    public FileMetadata getFile(String fileId) {
        return files.get(fileId);
    }
}
