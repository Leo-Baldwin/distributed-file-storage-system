package com.leo.dfss;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes framed messages to a TCP stream using following format:
 *
 * [4 bytes]    = header length (int)
 * [N bytes]    = JSON header (UTF-8) containing metadata about message, where N = header length
 * [N bytes]    = optional body bytes, where N = body length embedded in JSON header metadata
 */
public class TcpMessageWriter {

    private static final Gson GSON = new Gson();

    private final DataOutputStream out;

    public TcpMessageWriter(OutputStream outputStream) {
        this.out = new DataOutputStream(outputStream);
    }

    public void send(Message header, byte[] body) throws IOException {
        int bodyLength = (body != null) ? body.length : 0;

        // Convert header (Message) to a JsonObject so the bodyLength can be injected
        JsonObject headerJson = GSON.toJsonTree(header).getAsJsonObject();
        headerJson.addProperty("bodyLength", bodyLength);

        // Serialise header JSON to bytes
        String headerString = GSON.toJson(headerJson);
        byte[] headerBytes = headerString.getBytes(StandardCharsets.UTF_8);
        int headerLength = headerBytes.length;

        // 1) Write the 4-byte header length
        out.writeInt(headerLength);

        // 2) Write the header bytes
        out.write(headerBytes);

        // 3) Write the optional body bytes
        if (bodyLength > 0) {
            out.write(body);
        }

        // Ensure all bytes are sent
        out.flush();
    }
}
