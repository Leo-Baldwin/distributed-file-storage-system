package com.leo.dfss.protocol;

/** CHUNK_UPLOAD (client → node). The body carries the chunk bytes, which must hash to {@code hash}. */
public record ChunkUploadRequest(String hash) {
}
