package com.leo.dfss.coordinator;

import com.leo.dfss.domain.FileMetadata;
import com.leo.dfss.domain.NodeInfo;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Server that accepts multiple connections and gives each one a Connection thread.
 * Keeps a live registry of client and node connections.
 */
public class CoordinatorServer {

    public final int port;

    // Global file registry: fileId -> FileMetadata
    private final Map<String, FileMetadata> files = new ConcurrentHashMap<>();

    // Global node registry: nodeId -> NodeInfo
    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    // Node heartbeat configuration
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;    // 15 seconds
    private static final long SWEEP_INTERVAL_MS = 5_000;        // 5 seconds

    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor();

    // Global connections registry
    private final CopyOnWriteArrayList<CoordinatorConnection> connections = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;

    public CoordinatorServer(int port) {
        this.port = port;
    }

        public static void main (String[]args){
            new CoordinatorServer(9000).start();
        }

        public void start () {
            System.out.println("Starting CoordinatorServer...");

            // Try-with resources to ensure automatic closure of connection
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("CoordinatorServer listening on port: " + port);

                startNodeSweeper();

                int nextConnectionId = 1;

                while (running) {
                    // Blocks until client connects
                    Socket socket = serverSocket.accept();
                    System.out.println("Accepted connected from " + socket.getRemoteSocketAddress());

                    // Initialises new connection thread with a client socket, unique identifier and CoordinatorServer
                    CoordinatorConnection connection =
                            new CoordinatorConnection(socket, nextConnectionId++, this);

                    // Adds new connection thread to global connections registry and starts the thread
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
     * Method used to iterate through all threads in the connections list and shut them down.
     * Once all connections are closed the global registry is cleared.
     */
    public void shutdownAllConnections () {
            System.out.println("Shutting down all connections...");
            for (CoordinatorConnection connection : connections) {
                connection.shutdown();
            }
            connections.clear();
        }

    /**
     * Begins a sweeper that periodically checks for a nodes heartbeat, if no heartbeat is detected
     * for more than 15 seconds then it will mark the Nodes status as DOWN.
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
     * Handles the FILES_INIT_REQUEST command. Creates a new file record and returns the metadata.
     *
     * @param filename       name of the file
     * @param totalSizeBytes total size of the file
     * @param chunkSizeBytes size of each chunk
     * @return the metadata of the file
     */
    public FileMetadata initFileUpload (String filename,
                                            long totalSizeBytes,
                                            int chunkSizeBytes) {

            NodeInfo node = getAnyActiveNode();
            if (node == null) {
                throw new IllegalStateException("No active storage nodes available");
            }

            String fileId = UUID.randomUUID().toString();

            FileMetadata metadata =
                    new FileMetadata(fileId, filename, totalSizeBytes, chunkSizeBytes);

            metadata.setStatus(FileMetadata.Status.UPLOADING);

            metadata.setStorageHost(node.getHost());
            metadata.setStoragePort(node.getPort());

            files.put(fileId, metadata);

            System.out.println("New file record created: " + metadata);
            return metadata;
        }

    /**
     * Handles FILES_COMMIT. Marks file COMPLETE.
     *
     * @param fileId identifier for the file being committed
     * @return true if successfully committed, else false.
     */
    public boolean commitFile (String fileId){

            // TEMPORARY DEBUGGER START
            System.out.println("commitFile called for: " + fileId);
            FileMetadata m = files.get(fileId);
            System.out.println("metadata status BEFORE = " + (m == null ? "null" : m.getStatus()));
            // DEBUG END

            FileMetadata metadata = files.get(fileId);
            if (metadata == null) {
                return false;
            }

            metadata.setStatus(FileMetadata.Status.COMPLETE);

            // TEMP DEBUG
            System.out.println("metadata status AFTER = " + m.getStatus());
            // DEBUG END

            System.out.println("Committed file record: " + metadata);
            return true;
        }

    /**
     * Handles NODE_REGISTER.
     *
     * @param nodeId nodes unique identifier
     * @param host server host
     * @param port servers data port
     * @param capacityBytes the nodes storage capacity in bytes
     * @return true if node registered with CoordinatorServer successfully
     */
        public boolean registerNode(String nodeId, String host, int port, long capacityBytes) {
            long now =  System.currentTimeMillis();

            if (nodeId == null || nodeId.isBlank()) return false;
            if (host == null || host.isBlank()) return false;
            if (port <= 0) return false;

            NodeInfo node = new NodeInfo(nodeId, host, port, capacityBytes, now);
            nodes.put(nodeId, node);

            System.out.println("Node " + node.getNodeId() + " is registered");
            return true;
        }

    /**
     * Handle NODE_HEARTBEAT.
     *
     * @param nodeId unique identifier for the node that send the heartbeat message
     * @param timeStampEpochMs the time stamp that the heartbeat was sent by the node
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

    /** @return the first node with the status "UP" it can find in the node registry */
    public NodeInfo getAnyActiveNode() {
            for (NodeInfo node : nodes.values()) {
                if (node.getStatus() == NodeInfo.Status.UP) return node;
            }
            return null;
        }

        /**
         * Method to inspect all current nodes.
         *
         * @return node registry
         */
        public Map<String, NodeInfo> getNodes() {
            return nodes;
        }

        /** Method to lookup metadata of file by fileId.
         *
         * @param fileId identifier of file being retrieved
         * @return the metadata of specified file
         */
        public FileMetadata getFile (String fileId) {
            return files.get(fileId);
        }
    }