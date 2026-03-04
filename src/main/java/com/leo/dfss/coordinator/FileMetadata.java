package com.leo.dfss.coordinator;

import java.time.Instant;
import java.util.List;

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

    /**
     * Replication support: list of Nodes that store replicas of this file.
     * For now this will typically contain up to REPLICATION_FACTOR nodes.
     */
    private List<NodeEndpoint> replicaNodes;

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

    public List<NodeEndpoint> getReplicaNodes() {
        return replicaNodes;
    }

    public void setReplicaNodes(List<NodeEndpoint> replicaNodes) {
        this.replicaNodes = replicaNodes;
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
                ", replicaNodes=" + replicaNodes +
                ", status=" + status +
                ", createdAt=" + createdAt +
                '}';
    }

    /**
     * Lightweight representation of a storage Node used for replication.
     * This avoids coupling FileMetadata directly to NodeInfo.
     */
    public static class NodeEndpoint {
        private String nodeId;
        private String host;
        private int port;

        public NodeEndpoint() {}

        public NodeEndpoint(String nodeId, String host, int port) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        @Override
        public String toString() {
            return "NodeEndpoint{" +
                    "nodeId='" + nodeId + '\'' +
                    ", host='" + host + '\'' +
                    ", port=" + port +
                    '}';
        }
    }
}
