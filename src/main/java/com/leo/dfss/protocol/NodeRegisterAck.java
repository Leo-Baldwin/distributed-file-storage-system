package com.leo.dfss.protocol;

public class NodeRegisterAck {

    private String type = "NODE_REGISTER_ACK";
    private String status;   // "OK" or "ERROR"
    private String message;  // optional detail
    private int bodyLength = 0;

    public NodeRegisterAck() {}

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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}