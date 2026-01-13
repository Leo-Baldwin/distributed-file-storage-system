package com.leo.dfss;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class FramedTestClient {

    private static final String HOST = "localhost";
    private static final int PORT = 9000;

    public static void main(String[] args) {
        try (Socket socket = new Socket(HOST, PORT)) {
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());

            // Read server welcome
            printResponse(reader);

            // 1) PING
            writer.send(new Message("PING", null), null);
            printResponse(reader);

            // 2) SEND_BODY with a body payload
            byte[] body = "Hello body bytes!".getBytes(StandardCharsets.UTF_8);
            writer.send(new Message("SEND_BODY", "Here comes a body"), body);
            printResponse(reader);

            // 3) QUIT
            writer.send(new Message("QUIT", null), null);
            printResponse(reader);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void printResponse(TcpMessageReader reader) throws IOException {
        ReceivedMessage response  = reader.read();

        if (response == null) {
            System.out.println("Server closed connection.");
            return;
        }
        Message header = response.getHeader();
        System.out.println("Response: type=" + header.getType() + ", data=" + header.getData());
    }
}
