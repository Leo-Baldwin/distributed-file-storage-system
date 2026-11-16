package com.leo.dfss.coordinator;

/**
 * A file known to the Coordinator.
 *
 * Lifecycle: UPLOADING (created by FILES_INIT_REQUEST) → COMPLETE (after a successful FILES_COMMIT).
 * Only COMPLETE files can be downloaded. The ordered chunk hashes are stored separately and only
 * exist once the file is committed.
 */
public record FileMetadata(String fileId,
                           String fileName,
                           long totalSizeBytes,
                           int chunkSizeBytes,
                           Status status,
                           long createdEpochMs) {

    public enum Status {
        UPLOADING,
        COMPLETE
    }

    /** @return number of chunks this file is split into (the last may be partial; 0 for an empty file) */
    public long totalChunks() {
        return totalChunks(totalSizeBytes, chunkSizeBytes);
    }

    /** Ceiling division: a 10-byte file in 4-byte chunks has 3 chunks (4, 4, 2). */
    public static long totalChunks(long totalSizeBytes, int chunkSizeBytes) {
        if (chunkSizeBytes <= 0) {
            throw new IllegalArgumentException("chunkSizeBytes must be positive");
        }
        return (totalSizeBytes + chunkSizeBytes - 1) / chunkSizeBytes;
    }
}
