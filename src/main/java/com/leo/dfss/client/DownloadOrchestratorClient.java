package com.leo.dfss.client;

import com.google.gson.Gson;
import com.leo.dfss.config.ClientConfig;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side download orchestrator.
 *
 * Responsibilities:
 * - Requests file metadata from the Coordinator (FILES_GET_REQUEST).
 * - Downloads each chunk directly from a Node (CHUNK_DOWNLOAD).
 * - Reconstructs the original file locally by writing chunks in order.
 *
 * Replication support:
 * - If the Coordinator provides downloadSources, the client will try them in order per chunk.
 * - If downloadSources is missing/empty, the client falls back to legacy downloadHost/downloadPort.
 */
public class DownloadOrchestratorClient {

    private static final Gson gson = new Gson();

    private final ClientConfig config;

    public DownloadOrchestratorClient(ClientConfig config) {
        this.config = config;
    }

    /** Uses a Coordinator on localhost at the default port, with the default chunk size. */
    public DownloadOrchestratorClient() {
        this(ClientConfig.defaults());
    }

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

        // 1) Ask the Coordinator for file metadata and Node download location(s)
        FilesGetResponse info = getFileInfo(fileId);

        System.out.println("Downloading file: " + info.getFilename());
        System.out.println("Total chunks: " + info.getTotalChunks());

        if (info.getDownloadSources() != null && !info.getDownloadSources().isEmpty()) {
            System.out.println("downloadSources = " + info.getDownloadSources());
        }

        Files.createDirectories(outputDir);
        Path outputPath = outputDir.resolve(safeFileName(info.getFilename()));

        // Determine where to download chunks from. Prefer replication sources, fall back to legacy host/port.
        List<FilesGetResponse.NodeEndpoint> sources = new ArrayList<>();
        if (info.getDownloadSources() != null && !info.getDownloadSources().isEmpty()) {
            sources.addAll(info.getDownloadSources());
        } else {
            sources.add(new FilesGetResponse.NodeEndpoint("primary", info.getDownloadHost(), info.getDownloadPort()));
        }

        // 2) Download chunks in order into a temporary file, then move it into place, so a failed
        //    download never leaves a truncated file under the real name
        Path tempPath = Files.createTempFile(outputDir, ".dfs-download-", ".part");
        Map<FilesGetResponse.NodeEndpoint, NodeSession> sessions = new HashMap<>();
        Set<FilesGetResponse.NodeEndpoint> failed = new HashSet<>();
        try {
            try (var out = Files.newOutputStream(tempPath)) {
                for (int i = 0; i < info.getTotalChunks(); i++) {
                    out.write(downloadChunkFromAnySource(sources, sessions, failed, info.getFileId(), i));
                }
            }
            Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tempPath);
            throw new RuntimeException("Failed to reconstruct file", e);
        } finally {
            sessions.values().forEach(NodeSession::close);
        }

        System.out.println("Download complete: " + outputPath.toAbsolutePath());
    }

    /**
     * The filename comes from whoever uploaded the file, so never let it choose a path:
     * it must be a single plain name with no directory parts.
     */
    static String safeFileName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Refusing unsafe file name from server: " + name);
        }
        return name;
    }

    /**
     * Fetches file metadata and Node download location from the Coordinator.
     */
    private FilesGetResponse getFileInfo(String fileId) {
        try (Socket socket = new Socket(config.coordinatorHost(), config.coordinatorPort())) {

            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Read WELCOME message from Coordinator
            reader.read();

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
     * Downloads a single chunk by trying each available source in order, reusing one open
     * session per source. A source that fails is skipped for the rest of this download.
     */
    private byte[] downloadChunkFromAnySource(
            List<FilesGetResponse.NodeEndpoint> sources,
            Map<FilesGetResponse.NodeEndpoint, NodeSession> sessions,
            Set<FilesGetResponse.NodeEndpoint> failed,
            String fileId,
            int chunkIndex
    ) {
        Exception lastError = null;

        for (FilesGetResponse.NodeEndpoint source : sources) {
            if (failed.contains(source)) {
                continue;
            }
            try {
                NodeSession session = sessions.get(source);
                if (session == null) {
                    session = NodeSession.open(source.getHost(), source.getPort());
                    sessions.put(source, session);
                }
                return session.downloadChunk(fileId, chunkIndex);
            } catch (IOException e) {
                lastError = e;
                failed.add(source);
                NodeSession broken = sessions.remove(source);
                if (broken != null) broken.close();
                System.out.println("Chunk " + chunkIndex + " failed from " + source + " (" + e.getMessage()
                        + "), trying next source...");
            }
        }

        throw new RuntimeException("Failed to download chunk " + chunkIndex + " from all sources", lastError);
    }
}