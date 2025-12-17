package com.leo.dfss;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class FramedTestServer {

    private static final int PORT = 9020;

    public static void main(String[] args) {
        System.out.println("FramedTestServer listening on port: " + PORT);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            Socket socket = serverSocket.accept();
            System.out.println("Client connected from: " + socket.getRemoteSocketAddress());

            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            while (true) {
                ReceivedMessage received = reader.read();
                if (received == null) {
                    System.out.println("Client disconnected");
                    break;
                }

                Message header =  received.getHeader();
                byte[] body = received.getBody();

                System.out.println("Received message has type: " + header.getType() +
                                    ", data: " + header.getData() +
                                    ", body length: " + header.getBodyLength());

                if (body != null && body.length > 0) {
                    String bodyString = new String(body, StandardCharsets.UTF_8);
                    System.out.println("Received message body: " + bodyString);
                }

                if ("PING".equals(header.getType())) {
                    writer.send(new Message("PONG", "Hello from framed server"), null);
                } else if ("SEND_BODY".equals(header.getType())) {
                    writer.send(new Message("BODY_OK", "Received your body"), null);
                } else if ("QUIT".equals(header.getType())) {
                    writer.send(new Message("GOODBYE", "Closing connection"), null);
                    break;
                } else {
                    writer.send(new Message("ERROR", "Unknown type"), null);
                }
            }

            socket.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
