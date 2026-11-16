package com.leo.dfss.node;

import com.google.gson.Gson;
import com.leo.dfss.config.CoordinatorConfig;
import com.leo.dfss.config.NodeConfig;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.protocol.NodeHeartbeat;
import com.leo.dfss.protocol.NodeRegisterAck;
import com.leo.dfss.protocol.NodeRegisterRequest;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Storage Node server.
 *
 * Responsibilities:
 * - Accepts client TCP connections and delegates each connection to a {@link NodeConnection} thread.
 * - Stores and retrieves chunk bytes via {@link ChunkStore}.
 * - Maintains a background client connection to the Coordinator for registration and heartbeats,
 *   reconnecting (with a pause) and re-registering whenever that connection is lost.
 *
 * Threading model:
 * - One thread per incoming client connection (NodeConnection).
 * - One background daemon thread maintains the Coordinator connection and sends periodic heartbeats.
 */
public class NodeServer {

    private static final Gson gson = new Gson();
    private static final long RECONNECT_DELAY_MS = 2_000;

    private final NodeConfig config;
    private final String nodeId; // Persistent across restarts, stored in <dataDir>/node-id
    private final ChunkStore chunkStore;

    private final CopyOnWriteArrayList<NodeConnection> connections = new CopyOnWriteArrayList<>();

    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile ServerSocket serverSocket;
    private volatile Exception startupError;

    private volatile Socket coordinatorSocket;
    private Thread coordinatorThread;
    private volatile boolean registered;

    private volatile boolean running = true;

    /**
     * Creates a NodeServer instance.
     *
     * @throws IllegalStateException if the data directory or node ID file can't be created
     */
    public NodeServer(NodeConfig config) {
        this.config = config;
        this.chunkStore = new ChunkStore(config.dataDir());
        this.nodeId = loadOrCreateNodeId(config.dataDir());
    }

    /**
     * Convenience constructor: a node on the given port and data directory, using a Coordinator
     * on localhost at the default port.
     */
    public NodeServer(int port, Path baseDir) {
        this(NodeConfig.local(port, baseDir, CoordinatorConfig.DEFAULT_PORT));
    }

    public static void main(String[] args) {
        // Example: NodeServer on 9100 storing chunks under ./node-data
        new NodeServer(NodeConfig.DEFAULT_PORT, Path.of("node-data")).start();
    }

    /**
     * Reads this node's ID from {@code <dataDir>/node-id}, creating it on first start.
     * A stable ID lets the Coordinator recognise the node (and, later, the chunks it holds) after a restart.
     */
    static String loadOrCreateNodeId(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            Path idFile = dataDir.resolve("node-id");
            if (Files.exists(idFile)) {
                String id = Files.readString(idFile, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
            String id = "node-" + UUID.randomUUID();
            Files.writeString(idFile, id + "\n", StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or create node ID in " + dataDir, e);
        }
    }

    /**
     * Binds the listening port, starts Coordinator registration/heartbeats, then runs the accept loop.
     * Blocks until the server is shut down.
     */
    public void start() {
        System.out.println("NodeServer starting (node id " + nodeId + ", data " +
                chunkStore.getBaseDir().toAbsolutePath() + ")");

        // Bind first so we register the port we actually got (matters when configured with port 0)
        try (ServerSocket socket = new ServerSocket(config.port())) {
            serverSocket = socket;
            ready.countDown();
            System.out.println("NodeServer listening on port " + socket.getLocalPort());

            startCoordinatorClient();

            int nextConnectionId = 1;

            while (running) {
                // Blocks until a client connects
                Socket client = socket.accept();

                // One thread per client connection
                NodeConnection connection = new NodeConnection(client, nextConnectionId++, chunkStore);
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
                System.out.println("NodeServer stopped");
            }
        } finally {
            stopCoordinatorClient();
            shutdownAllConnections();
        }
    }

    private void startCoordinatorClient() {
        coordinatorThread = new Thread(() -> {
            while (running) {
                try (Socket socket = new Socket(config.coordinatorHost(), config.coordinatorPort())) {
                    coordinatorSocket = socket;
                    TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
                    TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

                    // Read WELCOME message from Coordinator
                    reader.read();

                    register(reader, writer);

                    // Heartbeat until the connection fails or the Coordinator stops recognising us
                    while (running) {
                        sendHeartbeat(reader, writer);
                        Thread.sleep(config.heartbeatIntervalMs());
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (running) {
                        System.out.println("Coordinator connection lost (" + e.getMessage() + "), retrying in "
                                + RECONNECT_DELAY_MS + " ms");
                    }
                } finally {
                    registered = false;
                    coordinatorSocket = null;
                }

                // Pause before reconnecting so a down Coordinator isn't hammered in a tight loop
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "NodeCoordinatorClient-" + nodeId);

        coordinatorThread.setDaemon(true);
        coordinatorThread.start();
    }

    /** Sends NODE_REGISTER and requires an OK acknowledgement. */
    private void register(TcpMessageReader reader, TcpMessageWriter writer) throws IOException {
        NodeRegisterRequest request = new NodeRegisterRequest();
        request.setNodeId(nodeId);
        request.setHost(config.advertiseHost()); // null: Coordinator uses the address we connect from
        request.setPort(serverSocket.getLocalPort());
        request.setCapacityBytes(diskSpace(true));
        request.setFailureDomain(config.failureDomain());

        writer.send(new Message("NODE_REGISTER", gson.toJson(request)), null);

        ReceivedMessage ack = reader.read();
        if (ack == null) {
            throw new IOException("Coordinator closed the connection during registration");
        }
        if (!"NODE_REGISTER_ACK".equals(ack.getHeader().getType())
                || !"OK".equalsIgnoreCase(gson.fromJson(ack.getHeader().getData(), NodeRegisterAck.class).getStatus())) {
            throw new IOException("Registration rejected: " + ack.getHeader().getData());
        }
        registered = true;
        System.out.println("Registered with Coordinator as " + nodeId + " (domain " + config.failureDomain() + ")");
    }

    /**
     * Sends one heartbeat. Any reply other than NODE_HEARTBEAT_ACK (for example "Unknown nodeId"
     * after a Coordinator restart) throws, which makes the client reconnect and re-register.
     */
    private void sendHeartbeat(TcpMessageReader reader, TcpMessageWriter writer) throws IOException {
        NodeHeartbeat hb = new NodeHeartbeat();
        hb.setNodeId(nodeId);
        hb.setTimestampEpochMs(System.currentTimeMillis());
        hb.setFreeBytes(diskSpace(false));

        writer.send(new Message("NODE_HEARTBEAT", gson.toJson(hb)), null);

        ReceivedMessage ack = reader.read();
        if (ack == null || !"NODE_HEARTBEAT_ACK".equals(ack.getHeader().getType())) {
            throw new IOException("Heartbeat not acknowledged: " +
                    (ack == null ? "connection closed" : ack.getHeader().getData()));
        }
    }

    /** Total or usable space on the disk holding the data directory, or 0 if unknown. */
    private long diskSpace(boolean total) {
        try {
            var store = Files.getFileStore(config.dataDir());
            return total ? store.getTotalSpace() : store.getUsableSpace();
        } catch (IOException e) {
            return 0;
        }
    }

    private void stopCoordinatorClient() {
        try {
            Socket s = coordinatorSocket;
            if (s != null) {
                s.close();
            }
        } catch (Exception ignored) {
        }

        if (coordinatorThread != null) {
            coordinatorThread.interrupt();
        }
    }

    private void shutdownAllConnections() {
        for (NodeConnection c : connections) {
            c.shutdown();
        }
        connections.clear();
    }

    /**
     * Waits until the listening socket is bound.
     *
     * @throws IllegalStateException if binding failed or did not happen in time
     */
    public void awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        if (!ready.await(timeout, unit)) {
            throw new IllegalStateException("Node did not start within " + timeout + " " + unit);
        }
        if (startupError != null) {
            throw new IllegalStateException("Node failed to start", startupError);
        }
    }

    /** @return the port actually bound. Call after {@link #awaitReady}. */
    public int getPort() {
        ServerSocket s = serverSocket;
        if (s == null) throw new IllegalStateException("Node is not listening yet");
        return s.getLocalPort();
    }

    public String getNodeId() {
        return nodeId;
    }

    /** @return true while the node is connected to and registered with the Coordinator */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * Signals the server to stop and closes the listening socket and all active connections.
     */
    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        stopCoordinatorClient();
        shutdownAllConnections();
    }
}
