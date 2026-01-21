package com.leo.dfss.node;

import com.leo.dfss.domain.ChunkStore;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

public class NodeServer {

    private final int port;
    private final ChunkStore chunkStore;

    private final CopyOnWriteArrayList<NodeConnection> connections = new CopyOnWriteArrayList<>();
    public boolean running = true;

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
            shutdownAllConnections();
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
        shutdownAllConnections();
    }
}
