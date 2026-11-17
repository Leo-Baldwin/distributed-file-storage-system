package com.leo.dfss.protocol;

/** CHUNK_UPLOAD_ACK (node → client). {@code status} is "OK" or "ERROR". */
public record ChunkUploadAck(String hash, String status, String message) {
}
