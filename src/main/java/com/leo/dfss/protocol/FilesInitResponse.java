package com.leo.dfss.protocol;

import java.util.List;

/**
 * FILES_INIT_RESPONSE (coordinator → client): the upload plan.
 *
 * @param uploadTargets nodes every chunk should be written to
 * @param warning       non-null if fewer copies than the replication factor can be placed
 */
public record FilesInitResponse(String fileId, int totalChunks, int chunkSizeBytes,
                                List<NodeEndpoint> uploadTargets, String warning) {
}
