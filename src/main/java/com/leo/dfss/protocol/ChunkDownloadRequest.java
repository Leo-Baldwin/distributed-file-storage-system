package com.leo.dfss.protocol;

/**
 * Protocol message for downloading a chunk from a node.
 */
public class ChunkDownloadRequest {
    private String fileId;
    private int chunkIndex;

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }
}