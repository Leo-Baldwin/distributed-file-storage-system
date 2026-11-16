package com.leo.dfss.client;

import com.google.gson.Gson;
import com.leo.dfss.config.ClientConfig;
import com.leo.dfss.protocol.*;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    private final ClientConfig config;

    public UploadOrchestratorClient(ClientConfig config) {
        this.config = config;
    }

    /** Uses a Coordinator on localhost at the default port, with the default chunk size. */
    public UploadOrchestratorClient() {
        this(ClientConfig.defaults());
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: UploadOrchestratorClient <filePath>");
            return;
        }

        Path filePath = Path.of(args[0]);
        String fileId = new UploadOrchestratorClient().uploadFile(filePath);
        System.out.println("Upload complete. FileId = " + fileId);
    }

    /**
     * Performs a full upload workflow for the given file.
     *
     * @param filePath path to the local file to upload
     */
    public String uploadFile(Path filePath) {
        long fileSize;
        String fileName;

        try {
            fileSize = Files.size(filePath);
            fileName = filePath.getFileName().toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file metadata", e);
        }

        System.out.println("Uploading file: " + fileName + " (" + fileSize + " bytes)");

        int chunkSizeBytes = config.chunkSizeBytes();

        FilesInitResponse init = initUploadWithCoordinator(fileName, fileSize, chunkSizeBytes);
        String fileId = init.getFileId();

        System.out.println("fileId " + fileId + ": " + init.getTotalChunks() + " chunk(s) of "
                + init.getChunkSizeBytes() + " bytes");

        uploadChunksToNodes(filePath, init);

        FilesCommitAck commitAck = commitUploadWithCoordinator(fileId);

        System.out.println("\n--- Commit result ---");
        System.out.println("status  = " + commitAck.getStatus());
        System.out.println("message = " + commitAck.getMessage());

        if (!"OK".equalsIgnoreCase(commitAck.getStatus())) {
            throw new RuntimeException("Commit failed: " + commitAck.getMessage());
        }
        return fileId;
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
        try (Socket socket = new Socket(config.coordinatorHost(), config.coordinatorPort())) {
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
     * Uploads all chunks of the file to the Node targets provided by the Coordinator.
     *
     * Replication behaviour:
     * - Each chunk is uploaded to ALL targets, in parallel, over one reused connection per target.
     * - Strict acknowledgement policy: every target must ACK OK before moving to the next chunk.
     * - For backward compatibility, if no targets are provided, the legacy uploadHost/uploadPort is used.
     */
    private void uploadChunksToNodes(Path filePath, FilesInitResponse init) {
        int chunkSizeBytes = init.getChunkSizeBytes();
        String fileId = init.getFileId();

        // Determine where to upload chunks. Prefer replication targets, fall back to legacy host/port.
        List<FilesInitResponse.NodeEndpoint> targets = new ArrayList<>();
        if (init.getUploadTargets() != null && !init.getUploadTargets().isEmpty()) {
            targets.addAll(init.getUploadTargets());
        } else {
            targets.add(new FilesInitResponse.NodeEndpoint("primary", init.getUploadHost(), init.getUploadPort()));
        }
        System.out.println("Uploading to " + targets.size() + " node(s): " + targets);

        List<NodeSession> sessions = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(targets.size());
        try (var in = Files.newInputStream(filePath)) {
            for (FilesInitResponse.NodeEndpoint target : targets) {
                sessions.add(NodeSession.open(target.getHost(), target.getPort()));
            }

            byte[] buffer = new byte[chunkSizeBytes];
            int chunkIndex = 0;
            while (true) {
                // readNBytes fills the whole buffer unless EOF is reached, so chunk boundaries
                // always match the Coordinator's plan (a plain read() may return short reads)
                int bytesRead = in.readNBytes(buffer, 0, chunkSizeBytes);
                if (bytesRead == 0) {
                    break; // EOF
                }

                byte[] chunkBytes = Arrays.copyOf(buffer, bytesRead);
                final int index = chunkIndex;

                List<Future<?>> writes = new ArrayList<>();
                for (NodeSession session : sessions) {
                    writes.add(pool.submit(() -> {
                        session.uploadChunk(fileId, index, chunkBytes);
                        return null;
                    }));
                }
                for (Future<?> write : writes) {
                    write.get();
                }

                chunkIndex++;
            }

            if (chunkIndex != init.getTotalChunks()) {
                throw new IllegalStateException("File changed during upload: read " + chunkIndex
                        + " chunks, expected " + init.getTotalChunks());
            }

        } catch (ExecutionException e) {
            throw new RuntimeException("Failed during chunk uploads", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Upload interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed during chunk uploads", e);
        } finally {
            pool.shutdownNow();
            sessions.forEach(NodeSession::close);
        }

        System.out.println("All " + init.getTotalChunks() + " chunk(s) uploaded.");
    }

    /**
     * Sends FILES_COMMIT to the Coordinator to mark the file COMPLETE.
     */
    private FilesCommitAck commitUploadWithCoordinator(String fileId) {
        try (Socket socket = new Socket(config.coordinatorHost(), config.coordinatorPort())) {
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
