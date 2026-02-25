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
 * Handles the operations required to download file chunks.
 */
public class DownloadOrchestratorClient {

    private static final Gson gson = new Gson();

    private final String coordinatorHost = "localhost";
    private final int coordinatorPort = 9000;

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Usage: DownloadClient <fileId> <outputDir>");
            return;
        }

        String fileId = args[0];
        Path outputDir = Path.of(args[1]);

        new DownloadOrchestratorClient().downloadFile(fileId, outputDir);
    }

    public void downloadFile(String fileId, Path outputDir) throws IOException {

        FilesGetResponse info = getFileInfo(fileId);

        System.out.println("Downloading file: " + info.getFilename());
        System.out.println("Chunks: " + info.getTotalChunks());

        Path outputPath = outputDir.resolve(info.getFilename());
        Files.createDirectories(outputDir);

        try (var out = java.nio.file.Files.newOutputStream(outputPath)) {
            for (int i = 0; i < info.getTotalChunks(); i++) {
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

        System.out.println("Download complete: " + outputPath.toAbsolutePath());
    }

    private FilesGetResponse getFileInfo(String fileId) {
        try (Socket socket = new Socket(coordinatorHost, coordinatorPort)) {

            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Read WELCOME from CoordinatorServer
            reader.read();

            FilesGetRequest req = new FilesGetRequest();
            req.setFileId(fileId);

            writer.send(new Message("FILES_GET_REQUEST", gson.toJson(req)), null);

            ReceivedMessage resp = reader.read();
            if (resp == null || resp.getHeader() == null) {
                throw new RuntimeException("Coordinator closed connection");
            }

            Message header = resp.getHeader();

            if (!"FILES_GET_RESPONSE".equals(header.getType())) {
                throw new RuntimeException("Coordinator error: " + header.getData());
            }

            return gson.fromJson(header.getData(), FilesGetResponse.class);

        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch file info", e);
        }
    }

    private byte[] downloadChunk(
            String host,
            int port,
            String fileId,
            int chunkIndex
    ) {
        try (Socket socket = new Socket(host, port)) {

            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Read WELCOME
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
                throw new RuntimeException("Node error: " + header.getData());
            }

            return resp.getBody();

        } catch (Exception e) {
            throw new RuntimeException("Failed to download chunk " + chunkIndex, e);
        }
    }
}