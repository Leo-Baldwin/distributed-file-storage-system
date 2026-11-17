package com.leo.dfss.coordinator;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory index of which node holds which chunk.
 *
 * Built entirely from what nodes report (their full inventory when they register, then each new
 * chunk as it arrives), so the nodes are the source of truth and the index is rebuilt
 * automatically after a Coordinator restart.
 *
 * Thread-safety: all methods are synchronised; returned collections are copies.
 */
public final class ChunkLocations {

    private final Map<String, Set<String>> nodesByHash = new HashMap<>();
    private final Map<String, Set<String>> hashesByNode = new HashMap<>();

    /** Records that {@code nodeId} holds each of {@code hashes}. */
    public synchronized void add(String nodeId, Collection<String> hashes) {
        Set<String> held = hashesByNode.computeIfAbsent(nodeId, k -> new HashSet<>());
        for (String hash : hashes) {
            if (held.add(hash)) {
                nodesByHash.computeIfAbsent(hash, k -> new HashSet<>()).add(nodeId);
            }
        }
    }

    /** Forgets everything {@code nodeId} held, before it re-sends its full inventory. */
    public synchronized void clearNode(String nodeId) {
        Set<String> held = hashesByNode.remove(nodeId);
        if (held == null) {
            return;
        }
        for (String hash : held) {
            Set<String> nodes = nodesByHash.get(hash);
            if (nodes != null) {
                nodes.remove(nodeId);
                if (nodes.isEmpty()) {
                    nodesByHash.remove(hash);
                }
            }
        }
    }

    /** @return the IDs of nodes reported to hold {@code hash} (possibly empty) */
    public synchronized Set<String> nodesFor(String hash) {
        Set<String> nodes = nodesByHash.get(hash);
        return nodes == null ? Set.of() : Set.copyOf(nodes);
    }

    /** @return how many of {@code hashes} no node has reported holding */
    public synchronized int countUnlocated(List<String> hashes) {
        int missing = 0;
        for (String hash : hashes) {
            if (!nodesByHash.containsKey(hash)) {
                missing++;
            }
        }
        return missing;
    }

    /** @return number of chunks {@code nodeId} has reported */
    public synchronized int chunkCount(String nodeId) {
        Set<String> held = hashesByNode.get(nodeId);
        return held == null ? 0 : held.size();
    }
}
