package com.leo.dfss.domain;

/**
 * Represents a storage node registered with the Coordinator.
 */
public class NodeInfo {

    public enum Status {
        UP,
        DOWN
    }

    private final String nodeId;
    private final String host;
    private final int port;
    private final long capacityBytes;

    private volatile long lastSeenEpochMs;
    private volatile Status status;

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

    // Getters

    public String getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public long getLastSeenEpochMs() {
        return lastSeenEpochMs;
    }

    public Status getStatus() {
        return status;
    }

    // State updates

    public void updateHeartbeat(long epochMs) {
        this.lastSeenEpochMs = epochMs;
        this.status = Status.UP;
    }

    public void markDown() {
        this.status = Status.DOWN;
    }

    @Override
    public String toString() {
        return "NodeInfo {" +
                "nodeId= " + nodeId + "\n" +
                "host= " + host + "\n" +
                "port= " + port + "\n" +
                "capacityBytes= " + capacityBytes + "\n" +
                "lastSeenEpochMs= " + lastSeenEpochMs + "\n" +
                "status= " + status + "\n" +
                "}";
    }
}
