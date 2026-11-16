package com.leo.dfss.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Disk persistence layer for Node chunk storage.
 *
 * Responsibilities:
 * - Writes chunk bytes to a deterministic on-disk layout.
 * - Reads chunk bytes back for download operations.
 * - Provides simple existence checks used for validation/debugging.
 *
 * On-disk layout:
 * <pre>
 *   <baseDir>/chunks/<fileId>/<chunkIndex>.bin
 * </pre>
 */
public class ChunkStore {

    private final Path baseDir;   // Base directory for all Node storage
    private final Path chunksDir; // Directory containing per-file chunk folders

    /**
     * Creates a ChunkStore rooted at the given base directory.
     * The chunk root directory is created as <baseDir>/chunks.
     *
     * @param baseDir base directory for Node storage
     */
    public ChunkStore(Path baseDir) {
        this.baseDir = baseDir;
        this.chunksDir = baseDir.resolve("chunks");
    }

    /**
     * Writes a chunk to disk.
     *
     * @param fileId unique file identifier of chunk
     * @param chunkIndex chunk number
     * @param data raw chunk bytes
     */
    public void writeChunk(String fileId, int chunkIndex, byte[] data) throws IOException {

        requireValidFileId(fileId);
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative");
        }
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }

        Path fileDir = chunksDir.resolve(fileId);
        Files.createDirectories(fileDir);

        Path chunkPath = fileDir.resolve(chunkIndex + ".bin");

        // Overwrite if the chunk already exists
        Files.write(chunkPath, data,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    /**
     * Reads a chunk from disk.
     *
     * @param fileId unique file identifier of chunk
     * @param chunkIndex chunk number
     * @return chunk bytes
     */
    public byte[] readChunk(String fileId, int chunkIndex) throws IOException {

        requireValidFileId(fileId);
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative");
        }

        Path chunkPath = chunksDir
                .resolve(fileId)
                .resolve(chunkIndex + ".bin");

        return Files.readAllBytes(chunkPath);
    }

    /**
     * Checks whether a specific chunk file exists on disk.
     *
     * @param fileId     unique file identifier
     * @param chunkIndex chunk number (0-based)
     * @return true if the chunk exists, otherwise false
     */
    public boolean chunkExists(String fileId, int chunkIndex) {
        if (!isValidFileId(fileId) || chunkIndex < 0) {
            return false;
        }

        Path chunkPath = chunksDir
                .resolve(fileId)
                .resolve(chunkIndex + ".bin");

        return Files.exists(chunkPath);
    }

    /**
     * File IDs are issued by the Coordinator as UUIDs. Accepting only that exact form means a
     * client-supplied ID can never contain path separators or "..", so it can't escape chunksDir.
     */
    private static final java.util.regex.Pattern FILE_ID =
            java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    static boolean isValidFileId(String fileId) {
        return fileId != null && FILE_ID.matcher(fileId).matches();
    }

    private static void requireValidFileId(String fileId) {
        if (!isValidFileId(fileId)) {
            throw new IllegalArgumentException("fileId must be a lowercase UUID");
        }
    }

    /**
     * @return base directory used by this ChunkStore
     */
    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * @return root directory that contains per-file chunk folders
     */
    public Path getChunksDir() {
        return chunksDir;
    }
}
