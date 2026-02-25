package com.leo.dfss.coordinator;

/**
 * In-memory record of a storage Node registered with the Coordinator.
 *
 * The Coordinator uses NodeInfo to:
 * - Track where file chunks can be stored/retrieved (host/port).
 * - Track node health using heartbeats (lastSeenEpochMs).
 * - Mark nodes UP/DOWN for simple availability checks.
 *
 * Thread-safety:
 * - Instances may be accessed by multiple Coordinator threads.
 * - {@link #lastSeenEpochMs} and {@link #status} are {@code volatile} so updates are visible across threads.
 */
public class NodeInfo {

    /**
     * Simple health status derived from heartbeats.
     */
    public enum Status {
        UP,
        DOWN
    }

    private final String nodeId;
    private final String host;
    private final int port;
    private final long capacityBytes;

    private volatile long lastSeenEpochMs; // Last heartbeat time (epoch millis)
    private volatile Status status; // Current availability state

    /**
     * Creates a new NodeInfo record.
     * Newly registered nodes default to {@link Status#UP}.
     */
    public NodeInfo(String nodeId,
                    String host,
                    int port,
                    long capacityBytes,
                    long lastSeenEpochMs) {

        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.capacityBytes = capacityBytes;
        this.lastSeenEpochMs = lastSeenEpochMs;
        this.status = Status.UP;
    }

    /**
     * @return unique identifier of the node
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * @return hostname/IP address where the node is reachable
     */
    public String getHost() {
        return host;
    }

    /**
     * @return TCP port where the node server listens
     */
    public int getPort() {
        return port;
    }

    /**
     * @return advertised capacity in bytes (informational in the current prototype)
     */
    public long getCapacityBytes() {
        return capacityBytes;
    }

    /**
     * @return timestamp of the most recent heartbeat seen by the Coordinator (epoch millis)
     */
    public long getLastSeenEpochMs() {
        return lastSeenEpochMs;
    }

    /**
     * @return current health status (UP/DOWN)
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Updates the last-seen timestamp from a heartbeat and marks the node as {@link Status#UP}.
     */
    public void updateHeartbeat(long epochMs) {
        this.lastSeenEpochMs = epochMs;
        this.status = Status.UP;
    }

    /**
     * Marks the node as {@link Status#DOWN}. Typically called by the heartbeat sweeper.
     */
    public void markDown() {
        this.status = Status.DOWN;
    }

    @Override
    public String toString() {
        return "NodeInfo{" +
                "nodeId=" + nodeId +
                ", host=" + host +
                ", port=" + port +
                ", capacityBytes=" + capacityBytes +
                ", lastSeenEpochMs=" + lastSeenEpochMs +
                ", status=" + status +
                '}';
    }
}
