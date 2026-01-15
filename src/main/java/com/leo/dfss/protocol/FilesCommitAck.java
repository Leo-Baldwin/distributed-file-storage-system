package com.leo.dfss.protocol;

public class FilesCommitAck {
    private String type = "FILES_COMMIT_ACK";
    private String fileId;
    private String status;
    private String message;
    private int bodyLength = 0;

    public FilesCommitAck() {
    }

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
