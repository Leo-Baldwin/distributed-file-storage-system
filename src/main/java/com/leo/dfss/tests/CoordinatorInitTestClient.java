package com.leo.dfss.tests;

import com.google.gson.Gson;
import com.leo.dfss.protocol.FilesInitRequest;
import com.leo.dfss.protocol.FilesInitResponse;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.net.Socket;

public class CoordinatorInitTestClient {

    private static final Gson gson = new Gson();

    private static final String COORDINATOR_HOST = "localhost";
    private static final int COORDINATOR_PORT = 9000;

    public static void main(String[] args) {
        // Hard-coded test values (we’ll use real file values in the orchestrator later)
        String filename = "example.txt";
        long totalSizeBytes = 12_345;
        int chunkSizeBytes = 4_096;

        try (Socket socket = new Socket(COORDINATOR_HOST, COORDINATOR_PORT)) {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // 1) Read WELCOME
            printResponse(reader);

            // 2) Build FILES_INIT_REQUEST (typed)
            FilesInitRequest req = new FilesInitRequest();
            req.setFilename(filename);
            req.setTotalSizeBytes(totalSizeBytes);
            req.setChunkSizeBytes(chunkSizeBytes);
            req.setBodyLength(0);

            // 3) Send init request to coordinator
            writer.send(new Message("FILES_INIT_REQUEST", gson.toJson(req)), null);

            // 4) Read response envelope
            ReceivedMessage respMsg = reader.read();
            if (respMsg == null) {
                System.out.println("Coordinator closed connection.");
                return;
            }

            Message respHeader = respMsg.getHeader();
            System.out.println("Envelope response: type=" + respHeader.getType());
            System.out.println("Envelope data: " + respHeader.getData());

            if ("FILES_INIT_RESPONSE".equals(respHeader.getType())) {
                FilesInitResponse resp = gson.fromJson(respHeader.getData(), FilesInitResponse.class);

                System.out.println("\n--- Parsed FILES_INIT_RESPONSE ---");
                System.out.println("fileId       = " + resp.getFileId());
                System.out.println("totalChunks  = " + resp.getTotalChunks());
                System.out.println("chunkSize    = " + resp.getChunkSizeBytes());
                System.out.println("uploadHost   = " + resp.getUploadHost());
                System.out.println("uploadPort   = " + resp.getUploadPort());
            } else {
                // likely ERROR
                System.out.println("\nCoordinator returned non-success type: " + respHeader.getType());
            }

            // 5) Quit cleanly
            writer.send(new Message("QUIT", "bye"), null);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printResponse(TcpMessageReader reader) throws Exception {
        ReceivedMessage rm = reader.read();
        if (rm == null) {
            System.out.println("Connection closed by server.");
            return;
        }
        Message h = rm.getHeader();
        System.out.println("Envelope: type=" + h.getType() + ", data=" + h.getData());
    }
}