package com.leo.dfss.protocol;

/**
 * Protocol message used to initiate the retrieval of a file.
 */
public class FilesGetRequest {
    private String fileId;

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }
}