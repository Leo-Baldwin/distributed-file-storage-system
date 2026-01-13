package com.leo.dfss;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.Socket;

/**
 * Handles a single client connection in its own thread.
 */
public class Connection extends Thread {

    private final Socket clientSocket;
    private final int connectionId; // for logging/identification
    private volatile boolean running = true;

    public Connection(Socket clientSocket, int connectionId) {
        this.clientSocket = clientSocket;
        this.connectionId = connectionId;
    }

    @Override
    public void run() {
        System.out.println("Connection " + connectionId + " created for " + clientSocket.getRemoteSocketAddress());

        try {
            TcpMessageReader reader = new TcpMessageReader(clientSocket.getInputStream());
            TcpMessageWriter writer = new  TcpMessageWriter(clientSocket.getOutputStream());

            writer.send(new Message("WELCOME", "Connection " + connectionId + " ready."), null);

            while (running) {
                ReceivedMessage receivedMessage = reader.read();

                if  (receivedMessage == null) {
                    System.out.println("[" + connectionId + "] Client disconnected.");
                    break;
                }

                Message header = receivedMessage.getHeader();
                byte[] body = receivedMessage.getBody();

                if (header == null || header.getType() == null) {
                    writer.send(new Message("ERROR", "Missing message type."), null);
                    continue;
                }

                String type = header.getType();
                String data = header.getData();

                switch (type) {
                    case "PING":
                        writer.send(new Message("PONG", "Pong (connection " + connectionId + ")"), null);
                        break;
                    case "ECHO":
                        writer.send(new Message("ECHO_RESPONSE", data != null ? data : ""), null);
                        break;
                    case "SEND_BODY":
                        // Example of client sending a body (binary bytes)
                        int length = body != null ? body.length : 0;
                        writer.send(new Message("BODY_OK", "Received body length = " + length), null);
                        break;

                    case "QUIT":
                        writer.send(new Message("GOODBYE", "Closing connection"), null);
                        running = false; // break loop
                        break;

                    default:
                        writer.send(new Message("ERROR", "Unknown message type: " + type), null);
                        break;
                }
            }
        } catch (IOException e) {
            System.out.println("[" + connectionId + "] Connection error: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Connection " + connectionId + " closed.");
    }

    public void shutdown() {
        running = false;
        try {
            clientSocket.close();
        } catch (IOException e) {
            // ignore
        }
    }
}
