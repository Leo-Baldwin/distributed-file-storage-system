package com.leo.dfss.protocol;

public class NodeHeartbeat {

    private String type = "NODE_HEARTBEAT";
    private String nodeId;
    private long timestampEpochMs;
    private long freeBytes; // optional but useful
    private int bodyLength = 0;

    public NodeHeartbeat() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public long getTimestampEpochMs() {
        return timestampEpochMs;
    }

    public void setTimestampEpochMs(long timestampEpochMs) {
        this.timestampEpochMs = timestampEpochMs;
    }

    public long getFreeBytes() {
        return freeBytes;
    }

    public void setFreeBytes(long freeBytes) {
        this.freeBytes = freeBytes;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}