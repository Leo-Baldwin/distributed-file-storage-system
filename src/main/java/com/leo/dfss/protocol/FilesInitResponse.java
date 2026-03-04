package com.leo.dfss.protocol;

import java.util.List;

public class FilesInitResponse {

    /**
     * Response protocol message sent back by the Coordinator in response to a FileInitRequest message.
     */
    private String type = "FILES_INIT_RESPONSE";
    private String fileId;
    private int totalChunks;
    private int chunkSizeBytes;
    private String uploadHost;
    private int uploadPort;

    /**
     * Replication support: list of nodes where chunks should be uploaded.
     * If present, clients should upload to ALL targets.
     */
    private List<NodeEndpoint> uploadTargets;
    private int bodyLength = 0;

    public FilesInitResponse() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
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

    public String getUploadHost() {
        return uploadHost;
    }

    public void setUploadHost(String uploadHost) {
        this.uploadHost = uploadHost;
    }

    public int getUploadPort() {
        return uploadPort;
    }

    public void setUploadPort(int uploadPort) {
        this.uploadPort = uploadPort;
    }

    public List<NodeEndpoint> getUploadTargets() {
        return uploadTargets;
    }

    public void setUploadTargets(List<NodeEndpoint> uploadTargets) {
        this.uploadTargets = uploadTargets;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }

    /**
     * Lightweight node endpoint returned by the Coordinator for uploads.
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
