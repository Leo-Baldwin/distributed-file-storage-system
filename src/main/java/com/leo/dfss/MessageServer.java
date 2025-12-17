package com.leo.dfss;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Server that accepts multiple clients and gives each one a Connection thread.
 */
public class MessageServer {

    public static final int PORT = 9000;
    private final List<Connection> connections = new ArrayList<>();

    private volatile boolean running = true;

    public static void main(String[] args) {
        MessageServer server = new MessageServer();
        server.start();
    }

    public void start() {
        System.out.println("Starting MessageServer on port: " + PORT);

        // Try-with resources to ensure automatic closure of connection
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Waiting for connections on port: " + PORT);

            int nextConnectionId = 1;

            while (running) {
                // Blocks until client connects
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected from " + clientSocket.getRemoteSocketAddress());

                Connection connection = new Connection(clientSocket, nextConnectionId++);
                connections.add(connection);
                connection.start();
            }

        } catch (IOException e) {
            if (running)  {
                e.printStackTrace();
            } else {
                System.out.println("Server stopped running.");
            }
        } finally {
            shutdownAllConnections();
        }
    }

    public void shutdownAllConnections() {
        System.out.println("Shutting down all connections on port: " + PORT);
        synchronized (connections) {
            for (Connection connection : connections) {
                connection.shutdown();
            }
        } connections.clear();
    }

    public void stopServer() {
        running = false;
    }
}
