package com.leo.dfss.protocol;

/** CHUNK_DOWNLOAD (client → node): ask for the chunk with this hash. */
public record ChunkDownloadRequest(String hash) {
}
