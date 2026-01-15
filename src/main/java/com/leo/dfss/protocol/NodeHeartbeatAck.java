package com.leo.dfss.protocol;

public class NodeHeartbeatAck {

    private String type = "NODE_HEARTBEAT_ACK";
    private String status; // "OK"
    private long serverTimeEpochMs;
    private int bodyLength = 0;

    public NodeHeartbeatAck() {}

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getServerTimeEpochMs() {
        return serverTimeEpochMs;
    }

    public void setServerTimeEpochMs(long serverTimeEpochMs) {
        this.serverTimeEpochMs = serverTimeEpochMs;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}