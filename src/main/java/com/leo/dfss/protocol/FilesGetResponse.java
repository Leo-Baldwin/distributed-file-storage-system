package com.leo.dfss.protocol;

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
}