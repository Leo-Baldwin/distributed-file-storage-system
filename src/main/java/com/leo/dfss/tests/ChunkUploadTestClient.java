package com.leo.dfss.tests;

import com.google.gson.Gson;
import com.leo.dfss.protocol.ChunkUploadRequest;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class ChunkUploadTestClient {

    public static final Gson gson = new Gson();

    public static final String NODE_HOST = "localhost";
    private static final int NODE_PORT = 9100;

    public static void main(String[] args) {
        String fileId = "test-file-" + UUID.randomUUID();
        int chunkIndex = 0;

        byte[] chunkBytes = "Hello from DFSS chunk upload!".getBytes(StandardCharsets.UTF_8);

        try (Socket socket = new Socket(NODE_HOST, NODE_PORT)) {

            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // 1) Read WELCOME message
            printResponse(reader);

            // 2) Build CHUNK_UPLOAD header
            ChunkUploadRequest request = new ChunkUploadRequest();
            request.setFileId(fileId);
            request.setChunkIndex(chunkIndex);
            request.setBodyLength(chunkBytes.length);

            // 3) Send framed message (header + body)
            writer.send(new Message("CHUNK_UPLOAD", gson.toJson(request)), chunkBytes);

            // 4) Read acknowledgement (ACK) of upload
            printResponse(reader);

            // 5) Quit cleanly
            writer.send(new Message("QUIT", "bye"), null);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printResponse(TcpMessageReader reader) throws Exception {
        ReceivedMessage receivedMessage = reader.read();
        if (receivedMessage == null) {
            System.out.println("Connection closed");
            return;
        }

        Message header =  receivedMessage.getHeader();
        System.out.println(
                "Response: type= " + header.getType() +
                ", data= " + header.getData()
        );
    }
}
