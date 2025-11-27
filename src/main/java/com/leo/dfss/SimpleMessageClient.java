package com.leo.dfss;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Represent a client that connects to a server over TCP and communicated with JSON messages.
 */
public class SimpleMessageClient {

    private static final String HOST = "localhost";
    private static final int PORT = 9000;
    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        System.out.println("Connecting to SimpleMessageServer on " + HOST + ":" + PORT + "...");

        try (
                Socket socket = new Socket(HOST, PORT);
                BufferedReader serverIn = new BufferedReader(
                        new InputStreamReader(socket.getInputStream())
                );
                PrintWriter serverOut = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader userIn = new BufferedReader(
                        new InputStreamReader(System.in)
                )
        ) {
            // Read the welcome message from the server
            String welcome = serverIn.readLine();
            System.out.println("Server: " +  welcome);

            while (true) {
                // Simple console menu for user
                System.out.println("Choose an action.");
                System.out.println("1. PING");
                System.out.println("2. ECHO a message");
                System.out.println("3. Get server TIME");
                System.out.println("4. QUIT");
                System.out.print("Enter choice form 1-4: ");

                String choice = userIn.readLine();
                if (choice == null) {
                    break;
                }

                Message request;

                // Turn your menu choice into a Message object
                switch (choice) {
                    case "1":
                        request = new Message("PING", null);
                        break;
                    case "2":
                        System.out.print("Enter message to echo: ");
                        String msg = userIn.readLine();
                        request = new Message("ECHO", msg);
                        break;
                    case "3":
                        request = new Message("TIME", null);
                        break;
                    case "4":
                        request = new Message("QUIT", null);
                        break;
                    default:
                        System.out.println("Invalid choice.");
                        continue;
                }

                // Convert Message -> JSON and send to server
                String json =  GSON.toJson(request);
                serverOut.println(json);

                // Read one JSON line back from the server
                String responseLine = serverIn.readLine();
                if (responseLine == null) {
                    System.out.println("Server closed the connection.");
                    break;
                }

                // Convert JSON -> Message object
                Message response = GSON.fromJson(responseLine, Message.class);

                // Show user the response message type and data
                System.out.println("Response type: " + response.getType());
                System.out.println("Response data: " + response.getData());

                if (response.getType().equals("QUIT")) {
                    System.out.println("Client closed the connection.");
                    break;
                }
            }
        } catch (IOException e)  {
            e.printStackTrace();
        }
    }
}
