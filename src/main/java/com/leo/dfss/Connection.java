package com.leo.dfss;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.LocalDate;

/**
 * Handles a single client connection in its own thread.
 */
public class Connection extends Thread {

    private static final Gson GSON = new Gson(); // New Gson object for wrapping JSON messages

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

        try (
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream())
                );
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
        ) {
            out.println("Connection  " + connectionId + " configured. Send JSON messages per line.");

            String line;
            while (running && (line = in.readLine()) != null) {
                System.out.println("[" + connectionId + "] Raw message from client: " + line);

                Message clientRequest;
                try {
                    clientRequest = GSON.fromJson(line, Message.class);
                } catch (JsonSyntaxException e) {
                    System.out.println("[" + connectionId + "] Invalid JSON received from client.");
                    Message error = new Message("ERROR", "Invalid JSON format");
                    out.println(GSON.toJson(error));
                    continue;
                }

                if (clientRequest == null || clientRequest.getType() == null) {
                    Message error = new Message("ERROR", "Message type is null");
                    out.println(GSON.toJson(error));
                    continue;
                }

                String type = clientRequest.getType();
                String data = clientRequest.getData();
                Message response;

                switch (type) {
                    case "PING":
                        // Health check, return Pong to notify client that server is alive
                        response = new Message("PONG", "Pong from server (connection: " + connectionId + ")");
                        break;
                    case "ECHO":
                        // Echo back whatever the client sent in data
                        response = new Message("ECHO_RESPONSE", data != null ? data : "");
                        break;
                    case "TIME":
                        // Return client server time
                        response = new Message("TIME_RESPONSE", LocalDate.now().toString());
                        break;
                    case "QUIT":
                        // Inform client the server is closing, then exit handleClient
                        response = new Message("QUIT_RESPONSE", "Closing connection");
                        out.println(GSON.toJson(response));
                        System.out.println("[" + connectionId + "] Client requested QUIT.");
                        running = false; // break loop
                        break;

                    default:
                        // Unknown message type
                        response = new Message("ERROR", "Unknown message type: " + type);
                        break;
                }

                if (!running) {
                    break;
                }

                String jsonResponse = GSON.toJson(response);
                out.println(jsonResponse);
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
