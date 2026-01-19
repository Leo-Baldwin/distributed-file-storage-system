package com.leo.dfss.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Class responsible for writing chunk bytes to disk and loading them back.
 */
public class ChunkStore {

    private final Path baseDir;
    private final Path chunksDir;

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

        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId cannot be null or blank");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative");
        }
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }

        Path fileDir = chunksDir.resolve(fileId);
        Files.createDirectories(fileDir);

        Path chunksPath = fileDir.resolve(chunkIndex + ".bin");

        // Overwrite if chunk exists at directory
        Files.write(chunksPath, data,
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

        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId cannot be null or blank");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("chunkIndex cannot be negative");
        }

        Path ChunkPath = chunksDir.resolve(fileId + ".bin");
        return Files.readAllBytes(ChunkPath);
    }

    /**
     * Checks whether a chunk exists.
     *
     * @param fileId unique file identifier of chunk
     * @param chunkIndex chunk number
     * @return true if chunk exists, else false
     */
    public Boolean chunkExists(String fileId, int chunkIndex) throws IOException {

        if (fileId == null || fileId.isBlank() ||  chunkIndex < 0) {
            return false;
        }

        Path ChunkPath = chunksDir.resolve(fileId + ".bin");
        return Files.exists(ChunkPath);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    public Path getChunksDir() {
        return chunksDir;
    }
}
