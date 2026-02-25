package com.leo.dfss.transport;

import com.leo.dfss.protocol.Message;

/**
 * Represents a fully read framed TCP message.
 *
 * A ReceivedMessage consists of:
 * - a deserialised {@link Message} header (application-layer metadata)
 * - an optional binary body (can be null if no body was transmitted)
 *
 * This class is used as the boundary object between the transport layer
 * (TcpMessageReader) and the application protocol handlers.
 */
public class ReceivedMessage {

    private final Message header;
    private final byte[] body; // may be null if no body was transmitted

    /**
     * Creates a new ReceivedMessage container.
     *
     * @param header parsed protocol header
     * @param body   optional binary body (can be null)
     */
    public ReceivedMessage(Message header, byte[] body) {
        this.header = header;
        this.body = body;
    }

    /** @return parsed protocol header */
    public Message getHeader() {
        return header;
    }

    /** @return binary body bytes, or null if none */
    public byte[] getBody() {
        return body;
    }

    /**
     * @return true if this message contains a binary body
     */
    public boolean hasBody() {
        return body != null && body.length > 0;
    }

    @Override
    public String toString() {
        return "ReceivedMessage{" +
                "header=" + header +
                ", bodyLength=" + (body == null ? 0 : body.length) +
                '}';
    }
}