package com.leo.dfss.protocol;

/**
 * FILES_COMMIT (client → coordinator). The body is the file's ordered chunk hashes,
 * packed with {@link com.leo.dfss.common.ChunkHashes#pack}.
 */
public record FilesCommitRequest(String fileId) {
}
