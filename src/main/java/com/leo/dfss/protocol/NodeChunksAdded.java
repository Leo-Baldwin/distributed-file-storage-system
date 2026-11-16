package com.leo.dfss.protocol;

/**
 * NODE_CHUNKS_ADDED (node → coordinator): this node now holds the chunks whose hashes are packed
 * in the body. Sent in batches after registration (the full inventory) and as new chunks arrive.
 */
public record NodeChunksAdded(String nodeId) {
}
