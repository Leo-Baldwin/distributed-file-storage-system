package com.leo.dfss.protocol;

public class FilesInitResponse {

    private String type = "FILES_INIT_RESPONSE";
    private String fileId;
    private int totalChunks;
    private int chunkSizeBytes;
    private String uploadHost;
    private int uploadPort;
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

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}
