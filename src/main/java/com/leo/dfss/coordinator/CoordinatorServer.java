package com.leo.dfss.coordinator;

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

    private final int port; // TCP port this CoordinatorServer listens on

    // Global file registry: fileId -> FileMetadata
    private final Map<String, FileMetadata> files = new ConcurrentHashMap<>();

    // Global node registry: nodeId -> NodeInfo
    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    // Node heartbeat configuration
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;    // 15 seconds
    private static final long SWEEP_INTERVAL_MS = 5_000;        // 5 seconds

    // Hardcoded replication factor
    private static final int REPLICATION_FACTOR = 3;

    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor();

    // Global connections registry
    private final CopyOnWriteArrayList<CoordinatorConnection> connections = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;

    public CoordinatorServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) {
        new CoordinatorServer(9000).start();
    }

    /**
     * Starts the Coordinator server accept loop and the node heartbeat sweeper.
     * This method blocks until the server is stopped or an unrecoverable I/O error occurs.
     */
    public void start() {
        System.out.println("Starting CoordinatorServer...");

        // Try-with-resources ensures the ServerSocket is closed when the server stops
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("CoordinatorServer listening on port: " + port);

            startNodeSweeper();

            int nextConnectionId = 1;

            while (running) {
                // Blocks until a client connects
                Socket socket = serverSocket.accept();
                System.out.println("Accepted connection from " + socket.getRemoteSocketAddress());

                // Create a per-connection handler thread (one thread per client socket)
                CoordinatorConnection connection =
                        new CoordinatorConnection(socket, nextConnectionId++, this);

                // Track and start the connection handler thread
                connections.add(connection);
                connection.start();
            }

        } catch (IOException e) {
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
     * If a node has not been seen for {@link #HEARTBEAT_TIMEOUT_MS} milliseconds it is marked DOWN.
     */
    private void startNodeSweeper() {
        sweeper.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();

            for (NodeInfo node : nodes.values()) {
                long age = now - node.getLastSeenEpochMs();

                if (node.getStatus() == NodeInfo.Status.UP && age > HEARTBEAT_TIMEOUT_MS) {
                    node.markDown();
                    System.out.println("Node " + node.getNodeId() + " is DOWN");
                }
            }
        }, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new in-memory file metadata record and assigns it to an active Node.
     *
     * @param filename       name of the file
     * @param totalSizeBytes total size of the file
     * @param chunkSizeBytes size of each chunk
     * @return the metadata of the file
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
     * Returns up to REPLICATION_FACTOR active nodes for storing replicas.
     * If fewer nodes are available than the replication factor,
     * the method degrades gracefully and returns the available nodes.
     */
    public List<NodeInfo> getActiveNodesForReplication() {

        List<NodeInfo> result = new ArrayList<>();

        for (NodeInfo node : nodes.values()) {
            if (node.getStatus() == NodeInfo.Status.UP) {
                result.add(node);

                if (result.size() == REPLICATION_FACTOR) {
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

        metadata.setStatus(FileMetadata.Status.COMPLETE);

        System.out.println("Committed file record: " + metadata);
        return true;
    }

    /**
     * Handles NODE_REGISTER.
     *
     * @param nodeId        nodes unique identifier
     * @param host          server host
     * @param port          servers data port
     * @param capacityBytes the nodes storage capacity in bytes
     * @return true if node registered with CoordinatorServer successfully
     */
    public boolean registerNode(String nodeId, String host, int port, long capacityBytes) {
        long now = System.currentTimeMillis();

        if (nodeId == null || nodeId.isBlank()) return false;
        if (host == null || host.isBlank()) return false;
        if (port <= 0) return false;

        NodeInfo node = new NodeInfo(nodeId, host, port, capacityBytes, now);
        nodes.put(nodeId, node);

        System.out.println("Node " + node.getNodeId() + " is registered");
        return true;
    }

    /**
     * Handles NODE_HEARTBEAT.
     *
     * @param nodeId           unique identifier for the node that sent the heartbeat message
     * @param timeStampEpochMs the timestamp that the heartbeat was sent by the node
     * @return true if heartbeat is successfully updated for that nodes NodeInfo registry, else false
     */
    public boolean handleHeartbeat(String nodeId, long timeStampEpochMs) {
        NodeInfo node = nodes.get(nodeId); // Retrieve node by its ID
        if (node == null) {
            return false; // node cannot be retrieved
        }

        node.updateHeartbeat(timeStampEpochMs);
        System.out.println("Heartbeat from node " + nodeId + " at " + timeStampEpochMs);
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