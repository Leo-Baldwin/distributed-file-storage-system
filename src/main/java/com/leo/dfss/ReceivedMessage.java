package com.leo.dfss;

/**
 * Represents a single framed message read from a TCP stream:
 * a JSON header (parsed into a Message object) and an optional body.
 */
public class ReceivedMessage {

    private final Message header;
    private final byte[] body; // may be null or empty if no body

    public ReceivedMessage(Message header, byte[] body) {
        this.header = header;
        this.body = body;
    }

    public Message getHeader() {
        return header;
    }

    public byte[] getBody() {
        return body;
    }
}