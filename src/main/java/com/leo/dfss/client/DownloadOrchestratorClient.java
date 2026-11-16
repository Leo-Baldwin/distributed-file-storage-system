package com.leo.dfss.client;

import com.leo.dfss.config.ClientConfig;
import com.leo.dfss.protocol.ChunkPlacements;
import com.leo.dfss.protocol.FilesGetRequest;
import com.leo.dfss.protocol.FilesGetResponse;
import com.leo.dfss.protocol.NodeEndpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side download orchestrator.
 *
 * 1. FILES_GET_REQUEST: the Coordinator returns the file's metadata and, for every chunk, the
 *    nodes currently holding it.
 * 2. Each chunk is fetched from one of its holders and verified against its hash. A node that
 *    fails or returns bad data is skipped and the next holder is tried.
 * 3. Chunks are written in order to a temporary file, which is moved into place only once the
 *    whole file has arrived.
 */
public class DownloadOrchestratorClient {

    // How long to wait for nodes to report chunks before failing a download
    private static final long HOLDER_WAIT_MS = 30_000;

    private final ClientConfig config;

    public DownloadOrchestratorClient(ClientConfig config) {
        this.config = config;
    }

    /** Uses a Coordinator on localhost at the default port. */
    public DownloadOrchestratorClient() {
        this(ClientConfig.defaults());
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: DownloadOrchestratorClient <fileId> <outputDir>");
            return;
        }
        new DownloadOrchestratorClient().downloadFile(args[0], Path.of(args[1]));
    }

    /**
     * Downloads a file into {@code outputDir}, under the name it was uploaded with.
     *
     * @return the path of the downloaded file
     */
    public Path downloadFile(String fileId, Path outputDir) throws IOException {
        FilesGetResponse info;
        List<ChunkPlacements.Placement> placements;

        // If some chunk has no known holder, nodes may still be re-sending their inventories (e.g.
        // just after a Coordinator restart), so wait briefly and ask again before giving up
        long deadline = System.currentTimeMillis() + HOLDER_WAIT_MS;
        while (true) {
            try (CoordinatorClient coordinator = CoordinatorClient.connect(config)) {
                info = coordinator.request("FILES_GET_REQUEST", new FilesGetRequest(fileId), null,
                        "FILES_GET_RESPONSE", FilesGetResponse.class);
                List<NodeEndpoint> nodes = info.nodes() == null ? List.of() : info.nodes();
                placements = ChunkPlacements.decode(coordinator.lastBody(), nodes.size());
            }
            if (placements.size() != info.totalChunks()) {
                throw new IOException("Coordinator listed " + placements.size() + " chunks, expected " + info.totalChunks());
            }
            long unheld = placements.stream().filter(p -> p.nodeIndexes().length == 0).count();
            if (unheld == 0 || System.currentTimeMillis() > deadline) {
                break; // if chunks are still unheld, downloadChunk reports which one
            }
            System.err.println(unheld + " chunk(s) have no available copy yet; waiting for nodes...");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for nodes", e);
            }
        }

        System.err.println("Downloading " + info.filename() + " (" + info.totalSizeBytes() + " bytes, "
                + info.totalChunks() + " chunk(s))");

        Files.createDirectories(outputDir);
        Path outputPath = outputDir.resolve(safeFileName(info.filename()));

        // Write to a temporary file, then move it into place, so a failed download never leaves a
        // truncated file under the real name
        Path tempPath = Files.createTempFile(outputDir, ".dfs-download-", ".part");
        Map<NodeEndpoint, NodeSession> sessions = new HashMap<>();
        Set<NodeEndpoint> failed = new HashSet<>();
        try {
            try (var out = Files.newOutputStream(tempPath)) {
                for (ChunkPlacements.Placement p : placements) {
                    out.write(downloadChunk(p, info.nodes(), sessions, failed));
                }
            }
            if (Files.size(tempPath) != info.totalSizeBytes()) {
                throw new IOException("Downloaded " + Files.size(tempPath) + " bytes, expected " + info.totalSizeBytes());
            }
            Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tempPath);
            throw e instanceof IOException io ? io : new IOException("Failed to reconstruct file: " + e.getMessage(), e);
        } finally {
            sessions.values().forEach(NodeSession::close);
        }

        System.err.println("Download complete: " + outputPath.toAbsolutePath());
        return outputPath;
    }

    /**
     * Fetches one chunk from any node holding it, reusing one session per node.
     * - A node whose connection fails is skipped for the rest of this download.
     * - A node that is reachable but has a bad or missing copy of this chunk is only skipped for
     *   this chunk: its other chunks may be the only good copies.
     */
    private byte[] downloadChunk(ChunkPlacements.Placement placement, List<NodeEndpoint> nodes,
                                 Map<NodeEndpoint, NodeSession> sessions, Set<NodeEndpoint> failed)
            throws IOException {
        IOException lastError = null;
        for (int index : placement.nodeIndexes()) {
            NodeEndpoint node = nodes.get(index);
            if (failed.contains(node)) {
                continue;
            }
            try {
                NodeSession session = sessions.get(node);
                if (session == null) {
                    session = NodeSession.open(node.host(), node.port());
                    sessions.put(node, session);
                }
                return session.downloadChunk(placement.hash());
            } catch (NodeSession.BadChunkException e) {
                lastError = e;
                System.err.println("Chunk " + placement.hash() + ": " + e.getMessage() + ", trying another copy...");
            } catch (IOException e) {
                lastError = e;
                failed.add(node);
                NodeSession broken = sessions.remove(node);
                if (broken != null) broken.close();
                System.err.println("Node " + node + " failed (" + e.getMessage() + "), using other copies");
            }
        }
        throw new IOException("No available copy of chunk " + placement.hash()
                + (lastError == null ? " (no node holding it is up)" : ""), lastError);
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
}
