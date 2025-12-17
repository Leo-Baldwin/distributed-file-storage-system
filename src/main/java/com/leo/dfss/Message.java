package com.leo.dfss;

/**
 * Represents a simple message sent over TCP as JSON.
 */
public class Message {
    private String type;
    private String data;
    private int bodyLength;


    // Gson needs a no-argument constructor
    public Message() {
    }

    public Message(String type, String data) {
        this.type = type;
        this.data = data;
        this.bodyLength = 0;
    }

    public String getType() {
        return type;
    }
    public String getData() {
        return data;
    }
    public int getBodyLength() {
        return bodyLength;
    }

    public void setType(String type) {
        this.type = type;
    }
    public void setData(String data) {
        this.data = data;
    }
    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }
}

