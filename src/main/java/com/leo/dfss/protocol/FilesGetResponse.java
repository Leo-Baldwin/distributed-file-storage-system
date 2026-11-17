package com.leo.dfss.protocol;

import java.util.List;

/**
 * FILES_GET_RESPONSE (coordinator → client). The body lists, for each chunk in order, its hash
 * and which of {@code nodes} hold it, encoded with {@link ChunkPlacements}.
 */
public record FilesGetResponse(String fileId, String filename, long totalSizeBytes, int totalChunks,
                               int chunkSizeBytes, List<NodeEndpoint> nodes) {
}
