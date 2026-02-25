package com.leo.dfss.node;

import com.google.gson.Gson;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.protocol.NodeHeartbeat;
import com.leo.dfss.protocol.NodeRegisterRequest;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Storage Node server.
 *
 * Responsibilities:
 * - Accepts client TCP connections and delegates each connection to a {@link NodeConnection} thread.
 * - Stores and retrieves chunk bytes via {@link ChunkStore}.
 * - Maintains a background client connection to the Coordinator for registration and heartbeats.
 *
 * Threading model:
 * - One thread per incoming client connection (NodeConnection).
 * - One background daemon thread maintains the Coordinator connection and sends periodic heartbeats.
 */
public class NodeServer {

    private static final Gson gson = new Gson();

    private final String coordinatorHost = "localhost"; // Coordinator host (control-plane)
    private final int coordinatorPort = 9000; // Coordinator port (control-plane)

    private final String nodeId = "node-" + UUID.randomUUID(); // Unique node identifier for registration/heartbeats

    private final int port;
    private final ChunkStore chunkStore;

    private final CopyOnWriteArrayList<NodeConnection> connections = new CopyOnWriteArrayList<>();

    private Socket coordinatorSocket;
    private TcpMessageReader coordinatorReader;
    private TcpMessageWriter coordinatorWriter;
    private Thread coordinatorThread;

    private volatile boolean running = true;

    /**
     * Creates a NodeServer instance.
     *
     * @param port    TCP port this NodeServer listens on for client chunk upload/download
     * @param baseDir base directory where chunk files are stored on disk
     */
    public NodeServer(int port, Path baseDir) {
        this.port = port;
        this.chunkStore = new ChunkStore(baseDir);
    }

    public static void main(String[] args) {
        // Example: NodeServer on 9100 storing chunks under ./node-data
        new NodeServer(9100, Path.of("node-data")).start();
    }

    /**
     * Starts the NodeServer accept loop and begins Coordinator registration/heartbeat reporting.
     */
    public void start() {
        System.out.println("NodeServer starting on port " + port + "...");
        System.out.println("ChunkStore base dir: " + chunkStore.getBaseDir().toAbsolutePath());
        System.out.println("Node id: " + nodeId);

        startCoordinatorClient();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("NodeServer listening on port " + port);

            int nextConnectionId = 1;

            while (running) {
                // Blocks until a client connects
                Socket socket = serverSocket.accept();
                System.out.println("Accepted connection from " + socket.getRemoteSocketAddress());

                // One thread per client connection
                NodeConnection connection = new NodeConnection(socket, nextConnectionId++, chunkStore);
                connections.add(connection);
                connection.start();
            }
        }   catch (IOException e) {
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
                try {
                    // Connect to Coordinator (control-plane) for registration and heartbeats
                    coordinatorSocket = new Socket(coordinatorHost, coordinatorPort);
                    coordinatorReader = new TcpMessageReader(coordinatorSocket.getInputStream());
                    coordinatorWriter = new TcpMessageWriter(coordinatorSocket.getOutputStream());

                    // Read WELCOME message from Coordinator
                    ReceivedMessage welcome = coordinatorReader.read();
                    if (welcome != null) {
                        System.out.println("Coordinator: " + welcome.getHeader().getType() +
                                " " + welcome.getHeader().getData());
                    }

                    // Register this Node (data-plane host/port) with the Coordinator
                    NodeRegisterRequest request = new NodeRegisterRequest();
                    request.setNodeId(nodeId);
                    // Host/IP that clients should use to reach this NodeServer (prototype uses localhost)
                    request.setHost("localhost");
                    request.setPort(port);
                    request.setCapacityBytes(50_000_000_000L);

                    coordinatorWriter.send(new Message("NODE_REGISTER", gson.toJson(request)), null);

                    ReceivedMessage requestAck = coordinatorReader.read();
                    if (requestAck != null) {
                        System.out.println("Coordinator: " + requestAck.getHeader().getType() +
                                " " + requestAck.getHeader().getData());
                    }

                    // Periodic heartbeat loop to indicate liveness
                    while (running) {
                        NodeHeartbeat hb = new NodeHeartbeat();
                        hb.setNodeId(nodeId);
                        hb.setTimestampEpochMs(System.currentTimeMillis());
                        hb.setFreeBytes(0L);

                        coordinatorWriter.send(new Message("NODE_HEARTBEAT", gson.toJson(hb)), null);

                        // Read heartbeat acknowledgement
                        ReceivedMessage hbAck = coordinatorReader.read();
                        if (hbAck != null) {
                            System.out.println("Coordinator: " + hbAck.getHeader().getType() +
                                    " " + hbAck.getHeader().getData());
                        }

                        // Heartbeat interval (ms)
                        Thread.sleep(5000);
                    }

                } catch (Exception e) {
                    if (running) {
                        System.out.println("Coordinator client error: " + e.getMessage());
                    }
                } finally {
                    // Ensure Coordinator socket resources are released before any reconnect attempt
                    try {
                        if (coordinatorSocket != null) {
                            coordinatorSocket.close();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, "NodeCoordinatorClient-" + nodeId);

        coordinatorThread.setDaemon(true);
        coordinatorThread.start();
    }

    private void stopCoordinatorClient() {
        try {
            if (coordinatorSocket != null) {
                coordinatorSocket.close();
            }
        } catch (Exception ignored) {}

        if (coordinatorThread != null) {
            coordinatorThread.interrupt();
        }
    }

    private void shutdownAllConnections() {
        System.out.println("Shutting down all node connections...");
        for (NodeConnection c : connections) {
            c.shutdown();
        }
        connections.clear();
    }

    /**
     * Signals the server to stop and closes all active connections.
     */
    public void shutdown() {
        running = false;
        stopCoordinatorClient();
        shutdownAllConnections();
    }
}
