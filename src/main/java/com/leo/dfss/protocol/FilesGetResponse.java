package com.leo.dfss.protocol;

import java.util.List;

/**
 * Response message sent back by Coordinator after a FilesGetRequest.
 */
public class FilesGetResponse {
    private String fileId;
    private String filename;
    private int totalChunks;
    private int chunkSizeBytes;
    private String downloadHost;
    private int downloadPort;

    /**
     * Replication support: list of nodes where chunks may be downloaded from.
     * Clients may try sources in order and fall back if a node is unavailable.
     */
    private List<NodeEndpoint> downloadSources;

    // Getters & Setters

    public String getFileId() {
        return fileId;
    }
    public void setFileId(String fileId) {
        this.fileId = fileId;
    }
    public String getFilename() {
        return filename;
    }
    public void setFilename(String filename) {
        this.filename = filename;
    }
    public int getTotalChunks() {
        return totalChunks;
    }
    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }
    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }
    public void setChunkSizeBytes(int chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }
    public String getDownloadHost() {
        return downloadHost;
    }
    public void setDownloadHost(String downloadHost) {
        this.downloadHost = downloadHost;
    }
    public int getDownloadPort() {
        return downloadPort;
    }
    public void setDownloadPort(int downloadPort) {
        this.downloadPort = downloadPort;
    }
    public List<NodeEndpoint> getDownloadSources() {
        return downloadSources;
    }
    public void setDownloadSources(List<NodeEndpoint> downloadSources) {
        this.downloadSources = downloadSources;
    }

    /**
     * Lightweight node endpoint returned by the Coordinator for downloads.
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