package com.leo.dfss.node;

import com.google.gson.Gson;
import com.leo.dfss.domain.ChunkStore;
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

public class NodeServer {

    private static final Gson gson = new Gson();

    private final String coordinatorHost = "localhost";
    private final int coordinatorPort = 9000;

    private final String nodeId = "node-" + UUID.randomUUID();

    private final int port;
    private final ChunkStore chunkStore;

    private final CopyOnWriteArrayList<NodeConnection> connections = new CopyOnWriteArrayList<>();

    private Socket coordinatorSocket;
    private TcpMessageReader coordinatorReader;
    private TcpMessageWriter coordinatorWriter;
    private Thread coordinatorThread;

    private volatile boolean running = true;

    public NodeServer(int port, Path baseDir) {
        this.port = port;
        this.chunkStore = new ChunkStore(baseDir);
    }

    public static void main(String[] args) {
        // Example: NodeServer on 9100 storing chunks under ./node-data
        new NodeServer(9100, Path.of("node-data")).start();
    }

    public void start() {
        System.out.println("NodeServer starting on port " + port + "...");
        System.out.println("ChunkStore base dir: " + chunkStore.getBaseDir().toAbsolutePath());
        System.out.println("Node id: " + nodeId);

        startCoordinatorClient();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("NodeServer listening on port: " + port);

            int nextConnectionId = 1;

            while (running) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted connection from " + socket.getRemoteSocketAddress());

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
                    coordinatorSocket = new Socket(coordinatorHost, coordinatorPort);
                    coordinatorReader = new TcpMessageReader(coordinatorSocket.getInputStream());
                    coordinatorWriter = new TcpMessageWriter(coordinatorSocket.getOutputStream());

                    // Read WELCOME message from Coordinator
                    ReceivedMessage welcome = coordinatorReader.read();
                    if (welcome != null) {
                        System.out.println("Coordinator: " + welcome.getHeader().getType() +
                                " " + welcome.getHeader().getData());
                    }

                    // Send NODE_REGISTER (with this NdeServer's data port)
                    NodeRegisterRequest request = new NodeRegisterRequest();
                    request.setNodeId(nodeId);
                    request.setHost("localhost");
                    request.setPort(port);
                    request.setCapacityBytes(50_000_000_000L);

                    coordinatorWriter.send(new Message("NODE_REGISTER", gson.toJson(request)), null);

                    ReceivedMessage requestAck = coordinatorReader.read();
                    if (requestAck != null) {
                        System.out.println("Coordinator: " + requestAck.getHeader().getType() +
                                " " + requestAck.getHeader().getData());
                    }

                    // Heartbeat loop
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

                        Thread.sleep(5000);
                    }

                } catch (Exception e) {
                    System.out.println("NodeServer stopped");
                } finally {
                    try {
                        coordinatorSocket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, "ThreadName");

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

    public void shutdown() {
        running = false;
        stopCoordinatorClient();
        shutdownAllConnections();
    }
}
