package com.leo.dfss.client;

import com.leo.dfss.common.ChunkHashes;
import com.leo.dfss.config.ClientConfig;
import com.leo.dfss.protocol.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Client-side upload orchestrator.
 *
 * 1. FILES_INIT_REQUEST: the Coordinator creates a file record and picks target nodes,
 *    one per failure domain.
 * 2. The file is split into fixed-size chunks; each is hashed (SHA-256) and written to every
 *    target in parallel. Nodes verify the hash and store each distinct chunk only once.
 * 3. FILES_COMMIT with the ordered chunk hashes: the Coordinator checks every chunk is held by a
 *    node, then marks the file COMPLETE.
 *
 * Progress goes to stderr; {@link #main} prints only the file ID to stdout.
 */
public class UploadOrchestratorClient {

    // Nodes report new chunks to the Coordinator within ~100 ms, so a commit can briefly race them
    private static final long COMMIT_RETRY_TIMEOUT_MS = 15_000;

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
            System.err.println("Usage: UploadOrchestratorClient <filePath>");
            return;
        }
        System.out.println(new UploadOrchestratorClient().uploadFile(Path.of(args[0])));
    }

    /**
     * Performs a full upload workflow for the given file.
     *
     * @return the new file's ID
     */
    public String uploadFile(Path filePath) {
        long fileSize;
        String fileName;
        try {
            fileSize = Files.size(filePath);
            fileName = filePath.getFileName().toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file metadata", e);
        }

        FilesInitResponse init;
        try (CoordinatorClient coordinator = CoordinatorClient.connect(config)) {
            init = coordinator.request("FILES_INIT_REQUEST",
                    new FilesInitRequest(fileName, fileSize, config.chunkSizeBytes()), null,
                    "FILES_INIT_RESPONSE", FilesInitResponse.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start upload with Coordinator: " + e.getMessage(), e);
        }

        System.err.println("Uploading " + fileName + " (" + fileSize + " bytes, " + init.totalChunks()
                + " chunk(s)) to " + init.uploadTargets());
        if (init.warning() != null) {
            System.err.println("WARNING: " + init.warning());
        }

        List<String> hashes = uploadChunks(filePath, init);
        commit(init.fileId(), hashes);
        return init.fileId();
    }

    /**
     * Uploads every chunk to all targets, in parallel across targets, over one connection each.
     * Strict acknowledgement: every target must ACK a chunk before moving on.
     *
     * @return the file's chunk hashes, in order
     */
    private List<String> uploadChunks(Path filePath, FilesInitResponse init) {
        List<NodeEndpoint> targets = init.uploadTargets();
        if (targets == null || targets.isEmpty()) {
            throw new IllegalStateException("Coordinator returned no upload targets");
        }

        int chunkSizeBytes = init.chunkSizeBytes();
        List<String> hashes = new ArrayList<>(init.totalChunks());
        List<NodeSession> sessions = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(targets.size());
        try (var in = Files.newInputStream(filePath)) {
            for (NodeEndpoint target : targets) {
                sessions.add(NodeSession.open(target.host(), target.port()));
            }

            byte[] buffer = new byte[chunkSizeBytes];
            while (true) {
                // readNBytes fills the whole buffer unless EOF is reached, so chunk boundaries
                // always match the Coordinator's plan (a plain read() may return short reads)
                int bytesRead = in.readNBytes(buffer, 0, chunkSizeBytes);
                if (bytesRead == 0) {
                    break; // EOF
                }

                byte[] chunk = Arrays.copyOf(buffer, bytesRead);
                String hash = ChunkHashes.sha256Hex(chunk);

                List<Future<?>> writes = new ArrayList<>();
                for (NodeSession session : sessions) {
                    writes.add(pool.submit(() -> {
                        session.uploadChunk(hash, chunk);
                        return null;
                    }));
                }
                for (Future<?> write : writes) {
                    write.get();
                }
                hashes.add(hash);
            }

            if (hashes.size() != init.totalChunks()) {
                throw new IllegalStateException("File changed during upload: read " + hashes.size()
                        + " chunks, expected " + init.totalChunks());
            }

        } catch (ExecutionException e) {
            throw new RuntimeException("Failed during chunk uploads: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Upload interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed during chunk uploads: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
            sessions.forEach(NodeSession::close);
        }
        return hashes;
    }

    /**
     * Commits the file. Retries while the Coordinator reports MISSING_CHUNKS, which only means the
     * nodes haven't finished reporting chunks they just stored.
     */
    private void commit(String fileId, List<String> hashes) {
        long deadline = System.currentTimeMillis() + COMMIT_RETRY_TIMEOUT_MS;
        long backoffMs = 50;
        try (CoordinatorClient coordinator = CoordinatorClient.connect(config)) {
            while (true) {
                FilesCommitAck ack = coordinator.request("FILES_COMMIT", new FilesCommitRequest(fileId),
                        ChunkHashes.pack(hashes), "FILES_COMMIT_ACK", FilesCommitAck.class);
                if ("OK".equals(ack.status())) {
                    System.err.println("Committed " + fileId);
                    return;
                }
                if (!"MISSING_CHUNKS".equals(ack.status()) || System.currentTimeMillis() > deadline) {
                    throw new RuntimeException("Commit failed: " + ack.message());
                }
                Thread.sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, 1_000);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to commit upload with Coordinator: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Commit interrupted", e);
        }
    }
}
