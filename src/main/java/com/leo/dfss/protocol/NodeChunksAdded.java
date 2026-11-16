package com.leo.dfss.protocol;

/**
 * NODE_CHUNKS_ADDED (node → coordinator): this node now holds the chunks whose hashes are packed
 * in the body. Sent in batches after registration (the full inventory) and as new chunks arrive.
 *
 * @param sessionId the registration this report belongs to; reports from a stale registration
 *                  are rejected so the node re-registers and re-sends its inventory
 */
public record NodeChunksAdded(String nodeId, String sessionId) {
}
