package com.leo.dfss.protocol;

public class FilesCommitRequest {
    private String type = "FILES_COMMIT";
    private String fileId;
    private int bodyLength = 0;

    public FilesCommitRequest() {}

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

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}
