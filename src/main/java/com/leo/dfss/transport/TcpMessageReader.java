package com.leo.dfss.transport;

import com.google.gson.Gson;
import com.leo.dfss.protocol.Message;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Reads framed protocol messages from a TCP byte stream.
 *
 * Framing format:
 * <pre>
 *   [4 bytes] headerLength
 *   [N bytes] header JSON (UTF-8), where N = headerLength
 *   [M bytes] optional body bytes, where M = bodyLength declared in the header
 * </pre>
 *
 * The returned {@link ReceivedMessage} contains:
 * - a deserialised {@link Message} header
 * - an optional binary body (byte[]), or {@code null} if no body was sent
 */
public class TcpMessageReader {

    private static final Gson GSON = new Gson();

    /** Largest JSON header accepted. Headers carry small control payloads only. */
    public static final int MAX_HEADER_BYTES = 64 * 1024;

    /** Largest binary body accepted. Must be at least as large as the biggest chunk size in use. */
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final DataInputStream in;

    /**
     * Creates a reader that consumes framed messages from the given InputStream.
     *
     * @param inputStream underlying TCP socket input stream
     */
    public TcpMessageReader(InputStream inputStream) {
        this.in = new DataInputStream(new BufferedInputStream(inputStream, 64 * 1024));
    }

    /**
     * Blocks until a full framed message is read.
     *
     * @return a {@link ReceivedMessage} (header + optional body), or {@code null} if EOF is reached
     * @throws IOException if the stream is corrupted or a framing rule is violated
     */
    public ReceivedMessage read() throws IOException {
        try {
            // 1) Read the 4-byte header length (EOFException indicates end-of-stream)
            int headerLength = in.readInt(); // throws EOFException if stream is closed.

            // Reject before allocating, so a peer can't make us allocate an arbitrary amount of memory
            if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) {
                throw new IOException("Invalid header length: " + headerLength);
            }

            // 2) Read exactly headerLength bytes of JSON header
            byte[] headerBytes = new byte[headerLength];
            in.readFully(headerBytes); // blocks until full header is read

            String headerString = new String(headerBytes, StandardCharsets.UTF_8);

            // 2b) Parse header JSON into a Message object
            Message header;
            try {
                header = GSON.fromJson(headerString, Message.class);
            } catch (com.google.gson.JsonParseException e) {
                throw new IOException("Invalid header JSON", e);
            }
            if (header == null) {
                throw new IOException("Invalid header JSON (parsed null)");
            }
            int bodyLength = header.getBodyLength();
            if (bodyLength < 0 || bodyLength > MAX_BODY_BYTES) {
                throw new IOException("Invalid body length: " + bodyLength);
            }

            // 3) If a body is declared, read exactly bodyLength bytes
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
