package com.leo.dfss;

public class Message {

    /**
     * Represents a simple message we send over TCP as JSON.
     *
     * Examples as JSON:
     * { "type": "PING", "data": null }
     * { "type": "ECHO", "data": "hello" }
     */
    private String type;
    private String data;

    // Gson needs a no-argument constructor
    public Message() {
    }

    public Message(String type, String data) {
        this.type = type;
        this.data = data;
    }

    // Getters
    public String getType() {
        return type;
    }

    public String getData() {
        return data;
    }

    // Setters (also needed by Gson)
    public void setType(String type) {
        this.type = type;
    }

    public void setData(String data) {
        this.data = data;
    }
}

