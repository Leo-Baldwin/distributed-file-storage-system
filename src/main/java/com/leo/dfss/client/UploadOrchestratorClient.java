package com.leo.dfss.client;

import com.google.gson.Gson;
import com.leo.dfss.protocol.*;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Client-side upload orchestrator.
 *
 * Responsibilities:
 * - Reads a local file and splits it into fixed-size chunks.
 * - Contacts the Coordinator to initialise the upload and obtain the target Node location.
 * - Uploads each chunk to the Node using CHUNK_UPLOAD (header + binary body).
 * - Commits the upload with the Coordinator so the file becomes downloadable.
 *
 */
public class UploadOrchestratorClient {

    private static final Gson gson = new Gson();

    private final String coordinatorHost = "localhost"; // Coordinator host (control-plane)
    private final int coordinatorPort = 9000; // Coordinator port (control-plane)

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: UploadOrchestratorClient <filePath>");
            return;
        }

        Path filePath = Path.of(args[0]);
        new UploadOrchestratorClient().uploadFile(filePath);
    }

    /**
     * Performs a full upload workflow for the given file.
     *
     * @param filePath path to the local file to upload
     */
    public void uploadFile(Path filePath) {
        long fileSize;
        String fileName;

        try {
            fileSize = Files.size(filePath);
            fileName = filePath.getFileName().toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file metadata", e);
        }

        System.out.println("Uploading file: " + fileName + " (" + fileSize + " bytes)");

        int chunkSizeBytes = 4096; // Fixed chunk size (4 KB) for this prototype

        FilesInitResponse init = initUploadWithCoordinator(fileName, fileSize, chunkSizeBytes);

        System.out.println("\n--- Coordinator upload plan ---");
        System.out.println("fileId      = " + init.getFileId());
        System.out.println("totalChunks = " + init.getTotalChunks());
        System.out.println("chunkSize   = " + init.getChunkSizeBytes());
        System.out.println("uploadHost  = " + init.getUploadHost());
        System.out.println("uploadPort  = " + init.getUploadPort());

        debugChunkFile(filePath, init.getChunkSizeBytes(), init.getTotalChunks());

        uploadChunksToNode(filePath, init);

        FilesCommitAck commitAck = commitUploadWithCoordinator(init.getFileId());

        System.out.println("\n--- Commit result ---");
        System.out.println("status  = " + commitAck.getStatus());
        System.out.println("message = " + commitAck.getMessage());

        if (!"OK".equalsIgnoreCase(commitAck.getStatus())) {
            throw new RuntimeException("Commit failed: " + commitAck.getMessage());
        }
    }

    /**
     * Sends FILES_INIT_REQUEST to the Coordinator to create a new file record and obtain
     * the Node upload destination.
     */
    private FilesInitResponse initUploadWithCoordinator(
            String fileName,
            long fileSize,
            int chunkSizeBytes
    ) {
        try (Socket socket = new Socket(coordinatorHost, coordinatorPort)) {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Read welcome message from coordinator server
            ReceivedMessage welcome = reader.read();
            if (welcome != null && welcome.getHeader() != null) {
                System.out.println("Coordinator -> " + welcome.getHeader().getType() + " " + welcome.getHeader().getData());
            }

            // Build typed request
            FilesInitRequest request = new FilesInitRequest();
            request.setFilename(fileName);
            request.setTotalSizeBytes(fileSize);
            request.setChunkSizeBytes(chunkSizeBytes);
            request.setBodyLength(0);

            // Send request
            writer.send(new Message("FILES_INIT_REQUEST", gson.toJson(request)), null);

            // Read response from server
            ReceivedMessage resp = reader.read();
            if (resp == null || resp.getHeader() == null) {
                throw new RuntimeException("Failed to read response from coordinator.");
            }

            Message header = resp.getHeader();
            String type = header.getType();
            String data = header.getData();

            if ("FILES_INIT_RESPONSE".equals(type)) {
                return gson.fromJson(data, FilesInitResponse.class);
            }

            // If response not read and returned then throw error
            throw new RuntimeException("Unrecognized response returned. " + type + ": " + data);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initiate upload with Coordinator. ", e);
        }
    }

    /**
     * Debug helper that chunks the file locally and prints the number/size of chunks.
     * This is useful to verify the local chunking logic matches the Coordinator plan.
     */
    private void debugChunkFile(Path filePath, int chunkSizeBytes, int expectedTotalChunks) {
        System.out.println("\n--- Local chunking (debug) ---");
        System.out.println("chunkSizeBytes = " + chunkSizeBytes);
        System.out.println("expectedChunks = " + expectedTotalChunks);

        int chunkIndex = 0;

        try (var in = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[chunkSizeBytes];

            while (true) {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break; // EOF
                }

                // Copy only the bytes actually read (important for last chunk)
                byte[] chunkBytes = java.util.Arrays.copyOf(buffer, bytesRead);

                System.out.println("chunk[" + chunkIndex + "] bytesRead=" + chunkBytes.length);

                chunkIndex++;
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to chunk file locally", e);
        }

        System.out.println("chunksRead = " + chunkIndex);

        if (chunkIndex != expectedTotalChunks) {
            System.out.println("WARNING: chunksRead != expectedTotalChunks");
        } else {
            System.out.println("OK: local chunking matches coordinator plan");
        }
    }

    /**
     * Uploads all chunks of the file directly to the Node provided by the Coordinator.
     */
    private void uploadChunksToNode(
            Path filePath,
            FilesInitResponse init
    ) {
        System.out.println("\n--- Uploading chunks to node ---");

        int chunkSizeBytes = init.getChunkSizeBytes();
        String fileId = init.getFileId();
        String host = init.getUploadHost();
        int port = init.getUploadPort();

        int chunkIndex = 0;

        // Stream the file and upload each chunk sequentially
        try (var in = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[chunkSizeBytes];

            while (true) {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break; // EOF
                }

                byte[] chunkBytes = Arrays.copyOf(buffer, bytesRead);

                // Build CHUNK_UPLOAD request header (declares bodyLength)
                ChunkUploadRequest req = new ChunkUploadRequest();
                req.setFileId(fileId);
                req.setChunkIndex(chunkIndex);
                req.setBodyLength(chunkBytes.length);

                // Connect to the Node for this chunk upload
                try (Socket socket = new Socket(host, port)) {
                    TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
                    TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

                    // Read welcome message from node server
                    ReceivedMessage welcome = reader.read();
                    if (welcome != null && welcome.getHeader() != null) {
                        System.out.println("Node -> " + welcome.getHeader().getType());
                    }

                    // Send CHUNK_UPLOAD (header + body)
                    writer.send(
                            new Message("CHUNK_UPLOAD", gson.toJson(req)),
                            chunkBytes
                    );

                    // Read CHUNK_UPLOAD_ACK
                    ReceivedMessage resp = reader.read();
                    if (resp == null || resp.getHeader() == null) {
                        throw new RuntimeException("Node closed connection without ACK");
                    }

                    Message header = resp.getHeader();
                    if (!"CHUNK_UPLOAD_ACK".equals(header.getType())) {
                        throw new RuntimeException("Unexpected response from node: " +
                                header.getType() + " " + header.getData());
                    }

                    ChunkUploadAck ack = gson.fromJson(header.getData(), ChunkUploadAck.class);
                    if (!"OK".equalsIgnoreCase(ack.getStatus())) {
                        throw new RuntimeException("Chunk upload failed: " + ack.getMessage());
                    }

                    System.out.println("Uploaded chunk " + chunkIndex +
                            " (" + bytesRead + " bytes)");
                }

                chunkIndex++;
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed during chunk uploads", e);
        }

        System.out.println("All chunks uploaded successfully.");
    }

    /**
     * Sends FILES_COMMIT to the Coordinator to mark the file COMPLETE.
     */
    private FilesCommitAck commitUploadWithCoordinator(String fileId) {
        try (Socket socket = new Socket(coordinatorHost, coordinatorPort)) {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Read welcome message from CoordinatorServer
            ReceivedMessage welcome = reader.read();
            if (welcome != null && welcome.getHeader() != null) {
                System.out.println("Coordinator -> " + welcome.getHeader().getType() + " " + welcome.getHeader().getData());
            }

            // Build commit request
            FilesCommitRequest req = new FilesCommitRequest();
            req.setFileId(fileId);
            req.setBodyLength(0);

            // Send FILES_COMMIT
            writer.send(new Message("FILES_COMMIT", gson.toJson(req)), null);

            // Read response
            ReceivedMessage resp = reader.read();
            if (resp == null || resp.getHeader() == null) {
                throw new RuntimeException("File commit failed");
            }

            Message header = resp.getHeader();
            String type = header.getType();
            String data = header.getData();

            if ("FILES_COMMIT_ACK".equals(type)) {
                return gson.fromJson(data, FilesCommitAck.class);
            }

            throw new RuntimeException("Coordinator returned " + type + ": " + data);

        } catch (Exception e) {
            throw new RuntimeException("Failed to commit upload with Coordinator. ", e);
        }
    }
}
