package com.leo.dfss.transport;

import com.google.gson.Gson;
import com.leo.dfss.protocol.Message;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Reads framed messages to a TCP stream using following format:
 *
 * [4 bytes]    = header length (int)
 * [N bytes]    = JSON header (UTF-8) containing metadata about message, where N = header length
 * [N bytes]    = optional body bytes, where N = body length embedded in JSON header metadata
 *
 * Returns a ReceivedMessage object containing:
 * - header as a Message object
 * - body as an optional byte[] (can be empty)
 */
public class TcpMessageReader {

    private static final Gson GSON = new Gson();

    private final DataInputStream in;

    public TcpMessageReader(InputStream inputStream) {
        this.in = new DataInputStream(inputStream);
    }

    /**
     * Blocks until a full framed message is read, or EOF.
     *
     * @return ReceivedMessage (header + optional body), or null if end of byte stream.
     */
    public ReceivedMessage read() throws IOException {
        try {
            // 1) Read the 4-byte header length
            int headerLength = in.readInt(); // throws EOFException if stream is closed.

            if (headerLength <= 0) {
                throw new IOException("Invalid header length");
            }

            // 2) Read exactly headerLength bytes of JSON header
            byte[] headerBytes = new byte[headerLength];
            in.readFully(headerBytes); // blocks until full header is read

            String headerString =  new String(headerBytes, StandardCharsets.UTF_8);

            // Parse the header JSON into Message class for convenience
            Message header = GSON.fromJson(headerString, Message.class);
            int bodyLength = header.getBodyLength();

            // 3) If body exists, read that many bytes
            byte[] body = null;
            if (bodyLength > 0) {
                body = new byte[bodyLength];
                in.readFully(body);
            }

            return new ReceivedMessage(header, body);

        } catch (EOFException e) {
            return null;
        }
    }
}
