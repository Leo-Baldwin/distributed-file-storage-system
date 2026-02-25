package com.leo.dfss.protocol;

/**
 * Response sent by a Node after a chunk download request has been processed.
 */
public class ChunkDownloadResponse {
    private String status;
    private String message;
    private int bodyLength;

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