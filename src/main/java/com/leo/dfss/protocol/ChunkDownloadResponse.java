package com.leo.dfss.protocol;

/** CHUNK_DOWNLOAD_RESPONSE (node → client). The body carries the chunk bytes. */
public record ChunkDownloadResponse(String hash, String status, String message) {
}
