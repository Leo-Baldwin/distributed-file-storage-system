package com.leo.dfss.protocol;

/** FILES_INIT_REQUEST (client → coordinator): start uploading a file. */
public record FilesInitRequest(String filename, long totalSizeBytes, int chunkSizeBytes) {
}
