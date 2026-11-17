package com.leo.dfss.protocol;

/**
 * FILES_COMMIT_ACK (coordinator → client).
 *
 * @param status        "OK", "MISSING_CHUNKS" (retry shortly: nodes may not have reported yet) or "ERROR"
 * @param missingChunks how many chunks no node has reported holding yet
 */
public record FilesCommitAck(String fileId, String status, String message, int missingChunks) {
}
