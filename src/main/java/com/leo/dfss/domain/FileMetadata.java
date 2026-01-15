package com.leo.dfss.domain;

import java.time.Instant;

/**
 * Represents the file metadata tracked by the Coordinator for each file being uploaded/stored.
 * This object stored no file data (chunks) - only information about the file itself.
 */
public class FileMetadata {

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

    private volatile Status status = Status.INIT;

    public FileMetadata(String fileId, String fileName, long totalSizeBytes, int chunkSizeBytes) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.totalSizeBytes = totalSizeBytes;
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalChunks = calculateTotalChunks(totalSizeBytes, chunkSizeBytes);
        this.createdAt = Instant.now();
    }

    private int calculateTotalChunks(long totalSizeBytes, int chunkSizeBytes) {
        if  (chunkSizeBytes <= 0) {
            throw new IllegalArgumentException("chunkSizeBytes must be greater than 0");
        }
        return (int) (totalSizeBytes / chunkSizeBytes);
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
    public Status getStatus() {
        return status;
    }
    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "FileMetadata{" +
                "fileId= " + fileId + "\n" +
                "fileName= " + fileName + "\n" +
                "totalSizeBytes= " + totalSizeBytes + "\n" +
                "chunkSizeBytes= " + chunkSizeBytes + "\n" +
                "totalChunks= " + totalChunks + "\n" +
                "status= " + status + "\n" +
                "createdAt= " + createdAt + "}";
    }
}
