package com.leo.dfss.protocol;

/**
 * Typed protocol message for uploading a chunk to a node.
 */
public class ChunkUploadRequest {

    private String type = "CHUNK_UPLOAD";

    private  String fileId;
    private int chunkIndex;

    // Must match the number of bytes in the message body
    private int bodyLength;

    public ChunkUploadRequest() {}

    public String  getType() {
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

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}
