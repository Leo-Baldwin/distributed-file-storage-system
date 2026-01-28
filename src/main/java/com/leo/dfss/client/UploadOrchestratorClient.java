package com.leo.dfss.client;

import com.google.gson.Gson;
import com.leo.dfss.protocol.FilesInitRequest;
import com.leo.dfss.protocol.FilesInitResponse;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

public class UploadOrchestratorClient {

    private static final Gson gson = new Gson();

    private final String coordinatorHost = "localhost";
    private final int coordinatorPort = 9000;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: UploadOrchestratorClient <filePath>");
            return;
        }

        Path filePath = Path.of(args[0]);
        new UploadOrchestratorClient().uploadFile(filePath);
    }

    public void uploadFile(Path filePath) {
        long fileSize;
        String fileName;

        try {
            fileSize = Files.size(filePath);
            fileName = filePath.getFileName().toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file metadata. " + e);
        }

        System.out.println("Uploading file: " + fileName + ". File size: " + fileSize + "bytes.");

        int chunkSizeBytes = 4096; // fixed (4KB)

        FilesInitResponse init = initUploadWithCoordinator(fileName, fileSize, chunkSizeBytes);

        System.out.println("\n--- Coordinator upload plan ---");
        System.out.println("fileId      = " + init.getFileId());
        System.out.println("totalChunks = " + init.getTotalChunks());
        System.out.println("chunkSize   = " + init.getChunkSizeBytes());
        System.out.println("uploadHost  = " + init.getUploadHost());
        System.out.println("uploadPort  = " + init.getUploadPort());
    }

    private FilesInitResponse initUploadWithCoordinator (
            String FileName,
            long fileSize,
            int chunkSizeBytes
    ) {
        try (Socket socket = new Socket(coordinatorHost, coordinatorPort)) {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Read welcome message from coordinator server
            ReceivedMessage welcome =reader.read();
            if (welcome != null && welcome.getHeader() != null) {
                System.out.println("Coordinator: " + welcome.getHeader().getType() + " " + welcome.getHeader().getData());
            }

            // Build typed request
            FilesInitRequest request = new FilesInitRequest();
            request.setFilename(FileName);
            request.setTotalSizeBytes(fileSize);
            request.setChunkSizeBytes(chunkSizeBytes);
            request.setBodyLength(0);

            // Send request
            writer.send(new Message("FILES_INIT_REQUEST", gson.toJson(request)), null);

            // Read response
            ReceivedMessage resp = reader.read();
            if (resp == null || resp.getHeader() == null) {
                throw new RuntimeException("Failed to read response from coordinator.");
            }

            Message header = resp.getHeader();
            String type = header.getType();
            String data = header.getData();

            if ("FILES_INIT_RESPONSE".equals(type)) {
                FilesInitResponse response = gson.fromJson(data, FilesInitResponse.class);

                return response;
            }

            // If response not read and returned then throw error
            throw new RuntimeException("Unrecognized response returned. " + type + ": " + data);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initiate upload with Coordinator. ", e);
        }
    }
}
