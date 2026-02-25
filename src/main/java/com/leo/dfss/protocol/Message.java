package com.leo.dfss.protocol;

/**
 * Protocol header message.
 *
 * This object is serialised as JSON and sent as the "header" portion of every framed TCP message.
 * It contains:
 * - {@link #type}: the message type discriminator (e.g., FILES_INIT_REQUEST)
 * - {@link #data}: optional JSON payload specific to the message type
 * - {@link #bodyLength}: length of the optional binary body in bytes (0 when no body is sent)
 */
public class Message {
    private String type;
    private String data;
    private int bodyLength;

    /**
     * No-argument constructor required for JSON deserialisation (Gson).
     */
    // Gson requires a public no-argument constructor for deserialisation
    public Message() {
    }

    /**
     * Convenience constructor for messages that have no binary body.
     * The {@link #bodyLength} is initialised to 0.
     */
    public Message(String type, String data) {
        this.type = type;
        this.data = data;
        this.bodyLength = 0;
    }

    /** @return message type discriminator */
    public String getType() {
        return type;
    }

    /** @param type message type discriminator */
    public void setType(String type) {
        this.type = type;
    }

    /** @return JSON data payload (may be null) */
    public String getData() {
        return data;
    }

    /** @param data JSON data payload (may be null) */
    public void setData(String data) {
        this.data = data;
    }

    /** @return declared length of the binary body in bytes */
    public int getBodyLength() {
        return bodyLength;
    }

    /** @param bodyLength declared length of the binary body in bytes */
    public void setBodyLength(int bodyLength) {
        this.bodyLength = bodyLength;
    }

    @Override
    public String toString() {
        return "Message{" +
                "type='" + type + '\'' +
                ", data=" + (data == null ? "null" : "<json>") +
                ", bodyLength=" + bodyLength +
                '}';
    }
}
