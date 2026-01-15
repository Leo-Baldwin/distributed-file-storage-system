package com.leo.dfss.protocol;

public class NodeRegisterRequest {

    private String type = "NODE_REGISTER";
    private String nodeId;
    private String host;
    private int port;
    private long capacityBytes;
    private int bodyLength = 0;

    public NodeRegisterRequest() {}

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

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public void setCapacityBytes(long capacityBytes) {
        this.capacityBytes = capacityBytes;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}
