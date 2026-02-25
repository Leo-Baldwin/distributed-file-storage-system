package com.leo.dfss.coordinator;

import java.time.Instant;

/**
 * In-memory metadata record for a file managed by the {@link CoordinatorServer}.
 *
 * This class intentionally stores NO file bytes. It only stores descriptive metadata
 * (file name, sizes, chunking parameters) and the storage location (host/port) of the
 * Node responsible for the file's chunks.
 *
 * Thread-safety:
 * - Instances may be accessed by multiple Coordinator connection threads.
 * - The {@link #status} field is {@code volatile} so lifecycle transitions are visible across threads.
 */
public class FileMetadata {

    /**
     * Lifecycle state for a file record.
     * Used by the Coordinator to prevent downloads until the upload has been committed.
     */
    public enum Status {
        INIT,           // File record created
        UPLOADING,      // Upload in progress
        COMPLETE        // Upload complete
    }

    private final String fileId;
    private final String fileName;
    private final long totalSizeBytes;
    private final int chunkSizeBytes;
    private final int totalChunks;
    private final Instant createdAt;
    private String storageHost; // Hostname/IP of the Node storing this file's chunks
    private int storagePort; // TCP port of the Node storing this file's chunks

    private volatile Status status = Status.INIT;

    /**
     * Creates a new file metadata record and derives the total number of chunks
     * based on the provided total size and chunk size.
     */
    public FileMetadata(String fileId, String fileName, long totalSizeBytes, int chunkSizeBytes) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.totalSizeBytes = totalSizeBytes;
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalChunks = calculateTotalChunks(totalSizeBytes, chunkSizeBytes);
        this.createdAt = Instant.now();
    }

    /**
     * Calculates how many fixed-size chunks are needed to store {@code totalSizeBytes}.
     * Uses ceiling division so any remainder produces an additional chunk.
     */
    private int calculateTotalChunks(long totalSizeBytes, int chunkSizeBytes) {
        if (chunkSizeBytes <= 0) {
            throw new IllegalArgumentException("chunkSizeBytes must be greater than 0");
        }

        return (totalSizeBytes == 0) ? 0 :
                (int) ((totalSizeBytes + chunkSizeBytes - 1) / chunkSizeBytes);
    }

    public String getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getTotalSizeBytes() {
        return totalSizeBytes;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getStorageHost() {
        return storageHost;
    }

    /**
     * Sets the Node host where this file's chunks are stored.
     */
    public void setStorageHost(String storageHost) {
        this.storageHost = storageHost;
    }

    public int getStoragePort() {
        return storagePort;
    }

    /**
     * Sets the Node TCP port where this file's chunks are stored.
     */
    public void setStoragePort(int storagePort) {
        this.storagePort = storagePort;
    }

    public Status getStatus() {
        return status;
    }

    /**
     * Updates the file lifecycle status (e.g., INIT -> UPLOADING -> COMPLETE).
     */
    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "FileMetadata{" +
                "fileId=" + fileId +
                ", fileName=" + fileName +
                ", totalSizeBytes=" + totalSizeBytes +
                ", chunkSizeBytes=" + chunkSizeBytes +
                ", totalChunks=" + totalChunks +
                ", storageHost=" + storageHost +
                ", storagePort=" + storagePort +
                ", status=" + status +
                ", createdAt=" + createdAt +
                '}';
    }
}
