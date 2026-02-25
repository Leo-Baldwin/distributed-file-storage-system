package com.leo.dfss.transport;

import com.google.gson.Gson;
import com.leo.dfss.protocol.Message;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes framed protocol messages to a TCP byte stream.
 *
 * Framing format:
 * <pre>
 *   [4 bytes] headerLength
 *   [N bytes] header JSON (UTF-8), where N = headerLength
 *   [M bytes] optional body bytes, where M = bodyLength declared in the header
 * </pre>
 *
 * This class is the symmetrical counterpart to {@link TcpMessageReader}.
 */
public class TcpMessageWriter {

    private static final Gson GSON = new Gson();

    private final DataOutputStream out;

    /**
     * Creates a writer that sends framed messages to the given OutputStream.
     *
     * @param outputStream underlying TCP socket output stream
     */
    public TcpMessageWriter(OutputStream outputStream) {
        this.out = new DataOutputStream(outputStream);
    }

    /**
     * Serialises and writes a framed message to the stream.
     *
     * @param header protocol header (will have bodyLength populated automatically)
     * @param body   optional binary payload (may be null)
     * @throws IOException if writing to the stream fails
     */
    public void send(Message header, byte[] body) throws IOException {
        if (header == null) {
            throw new IllegalArgumentException("Header cannot be null");
        }

        int bodyLength = (body != null) ? body.length : 0;
        header.setBodyLength(bodyLength);

        // 1) Serialise header object to JSON bytes
        String headerString = GSON.toJson(header);
        byte[] headerBytes = headerString.getBytes(StandardCharsets.UTF_8);
        int headerLength = headerBytes.length;

        // 2) Write 4-byte header length prefix
        out.writeInt(headerLength);

        // 3) Write header JSON bytes
        out.write(headerBytes);

        // 4) Write optional binary body bytes (if present)
        if (bodyLength > 0) {
            out.write(body);
        }

        // 5) Flush stream to ensure all framed bytes are transmitted
        out.flush();
    }
}
