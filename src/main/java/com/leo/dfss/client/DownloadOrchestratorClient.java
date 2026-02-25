package com.leo.dfss.client;

import com.google.gson.Gson;
import com.leo.dfss.protocol.ChunkDownloadRequest;
import com.leo.dfss.protocol.FilesGetRequest;
import com.leo.dfss.protocol.FilesGetResponse;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side download orchestrator.
 *
 * Responsibilities:
 * - Requests file metadata from the Coordinator (FILES_GET_REQUEST).
 * - Downloads each chunk directly from the Node (CHUNK_DOWNLOAD).
 * - Reconstructs the original file locally by writing chunks in order.
 *
 */
public class DownloadOrchestratorClient {

    private static final Gson gson = new Gson();

    private final String coordinatorHost = "localhost"; // Coordinator host (control-plane)
    private final int coordinatorPort = 9000; // Coordinator port (control-plane)

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Usage: DownloadOrchestratorClient <fileId> <outputDir>");
            return;
        }

        String fileId = args[0];
        Path outputDir = Path.of(args[1]);

        new DownloadOrchestratorClient().downloadFile(fileId, outputDir);
    }

    /**
     * Performs a full download workflow for the given fileId.
     *
     * @param fileId    identifier of the file to download
     * @param outputDir directory where the reconstructed file should be written
     */
    public void downloadFile(String fileId, Path outputDir) throws IOException {

        // 1) Ask the Coordinator for file metadata and Node download location
        FilesGetResponse info = getFileInfo(fileId);

        System.out.println("Downloading file: " + info.getFilename());
        System.out.println("Total chunks: " + info.getTotalChunks());

        Path outputPath = outputDir.resolve(info.getFilename());
        Files.createDirectories(outputDir);

        // 2) Download chunks in order and write them sequentially to reconstruct the file
        try (var out = java.nio.file.Files.newOutputStream(outputPath)) {
            for (int i = 0; i < info.getTotalChunks(); i++) {
                // Download the next chunk from the Node
                byte[] chunk = downloadChunk(
                        info.getDownloadHost(),
                        info.getDownloadPort(),
                        info.getFileId(),
                        i
                );

                out.write(chunk);
                System.out.println("Downloaded chunk: " + i);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to reconstruct file", e);
        }

        System.out.println("Download complete: " + outputPath.getFileName() + " -> " + outputPath.toAbsolutePath());
    }

    /**
     * Fetches file metadata and Node download location from the Coordinator.
     */
    private FilesGetResponse getFileInfo(String fileId) {
        try (Socket socket = new Socket(coordinatorHost, coordinatorPort)) {

            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Read WELCOME message from Coordinator
            ReceivedMessage welcome = reader.read();

            FilesGetRequest req = new FilesGetRequest();
            req.setFileId(fileId);

            writer.send(new Message("FILES_GET_REQUEST", gson.toJson(req)), null);

            ReceivedMessage resp = reader.read();
            if (resp == null || resp.getHeader() == null) {
                throw new RuntimeException("Coordinator closed the connection unexpectedly");
            }

            Message header = resp.getHeader();

            if (!"FILES_GET_RESPONSE".equals(header.getType())) {
                throw new RuntimeException("Coordinator error: " + header.getType() + " " + header.getData());
            }

            return gson.fromJson(header.getData(), FilesGetResponse.class);

        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch file info", e);
        }
    }

    /**
     * Downloads a single chunk from the Node server.
     */
    private byte[] downloadChunk(
            String host,
            int port,
            String fileId,
            int chunkIndex
    ) {
        try (Socket socket = new Socket(host, port)) {

            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Read WELCOME message from Node
            reader.read();

            ChunkDownloadRequest req = new ChunkDownloadRequest();
            req.setFileId(fileId);
            req.setChunkIndex(chunkIndex);

            writer.send(new Message("CHUNK_DOWNLOAD", gson.toJson(req)), null);

            ReceivedMessage resp = reader.read();
            if (resp == null || resp.getHeader() == null) {
                throw new RuntimeException("Node closed connection");
            }

            Message header = resp.getHeader();

            if (!"CHUNK_DOWNLOAD_RESPONSE".equals(header.getType())) {
                throw new RuntimeException("Node error: " + header.getType() + " " + header.getData());
            }

            return resp.getBody();

        } catch (Exception e) {
            throw new RuntimeException("Failed to download chunk " + chunkIndex, e);
        }
    }
}