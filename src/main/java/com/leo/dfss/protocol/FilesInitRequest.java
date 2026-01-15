package com.leo.dfss.protocol;

public class FilesInitRequest {

    private String type = "FILES_INIT_REQUEST";
    private String filename;
    private long totalSizeBytes;
    private int chunkSizeBytes;
    private int bodyLength = 0;

    public FilesInitRequest() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public long getTotalSizeBytes() {
        return totalSizeBytes;
    }

    public void setTotalSizeBytes(long totalSizeBytes) {
        this.totalSizeBytes = totalSizeBytes;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(int chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}
