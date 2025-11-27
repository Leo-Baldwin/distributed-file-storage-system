package com.leo.dfss;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDate;

/**
 * Represents a simple TCP server that receives and handles JSON messages from a client.
 */
public class SimpleMessageServer {
    private static final int PORT = 9000;
    private static final Gson GSON = new Gson(); // New Gson object for wrapping JSON messages

    public static void main(String[] args) {
        System.out.println("Starting SimpleMessageServer on port: " + PORT + "...");

        // Try-with resources to ensure automatic closure of connection
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server is listening on port: " + PORT);

            while (true) {
                // Blocks until client connects
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client has connected from  " + clientSocket.getRemoteSocketAddress());

                // Method to handle single client (no threads yet)
                handleClient(clientSocket);

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void handleClient(Socket clientSocket) {

        // Setup input reader out and output writer with try-with resources
        try (
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream())
                );
                PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)
                ) {

            // Inform the client the server is ready to receive JSON messages
            out.println("SimpleMessageServer ready to receive. Messages must be JSON per line.");

            String line;
            // Read one line at a time from the client
            while ((line = in.readLine()) != null) {
                System.out.println("Raw message from client: " + line);

                // Convert the raw JSON text -> GSON message object
                Message request;
                try {
                    request = GSON.fromJson(line, Message.class);
                } catch (JsonSyntaxException e) {
                    System.out.println("Invalid JSON format received from client.");
                    Message error = new Message("ERROR", "Invalid JSON format");
                    out.println(GSON.toJson(error));
                    continue; // skip to next message
                }

                // Basic validation
                if  (request == null || request.getType() == null) {
                    Message error = new Message("ERROR", "Message type is null");
                    out.println(GSON.toJson(error));
                    continue;
                }

                String type = request.getType();
                String data = request.getData();

                // Decide what to do based on message type
                Message response;

                switch (type) {
                    case "PING":
                        // Health check, return Pong to notify client that server is alive
                        response = new Message("PONG", "Server is alive!");
                        break;
                    case "ECHO":
                        // Echo back whatever the client sent in data
                        response = new Message("ECHO", data != null ? data : "");
                        break;
                    case "TIME":
                        // Return client server time
                        response = new Message("TIME_RESPONSE", LocalDate.now().toString());
                        break;
                    case "QUIT":
                        // Inform client the server is closing, then exit handleClient
                        response = new Message("QUIT_RESPONSE", "Closing connection");
                        out.println(GSON.toJson(response));
                        System.out.println("Client requested QUIT, Closing connection...");
                        return;
                    default:
                        // Unknown message type
                        response = new Message("ERROR", "Unknown message type");
                        break;
                }

                // Convert GSON Message -> JSON and sent it back
                out.println(GSON.toJson(response));
            }

        } catch (IOException e)  {
            e.printStackTrace();
        } finally {
            try {
                System.out.println("Closing connection to client...");
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
