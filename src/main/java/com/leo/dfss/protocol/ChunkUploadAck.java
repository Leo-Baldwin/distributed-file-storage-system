package com.leo.dfss.protocol;

/**
 * Response sent by Node after a chunk is written to disk.
 */
public class ChunkUploadAck {

    private String type = "CHUNK_UPLOAD_ACK";

    private String fileId;
    private int chunkIndex;

    private String status;   // "OK" or "ERROR"
    private String message;  // optional detail

    private int bodyLength = 0;

    public ChunkUploadAck() {}

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

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}