package com.leo.dfss.client;

import com.google.gson.Gson;
import com.leo.dfss.common.ChunkHashes;
import com.leo.dfss.protocol.ChunkDownloadRequest;
import com.leo.dfss.protocol.ChunkUploadAck;
import com.leo.dfss.protocol.ChunkUploadRequest;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * One open connection to a storage Node, reused for many chunk transfers.
 * Not thread-safe: use one session per thread at a time.
 */
final class NodeSession implements AutoCloseable {

    private static final Gson gson = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String label;
    private final Socket socket;
    private final TcpMessageReader reader;
    private final TcpMessageWriter writer;

    private NodeSession(String label, Socket socket) throws IOException {
        this.label = label;
        this.socket = socket;
        this.reader = new TcpMessageReader(socket.getInputStream());
        this.writer = new TcpMessageWriter(socket.getOutputStream());
    }

    /** Connects to a node and consumes its WELCOME message. */
    static NodeSession open(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            NodeSession session = new NodeSession(host + ":" + port, socket);
            ReceivedMessage welcome = session.reader.read();
            if (welcome == null || !"WELCOME".equals(welcome.getHeader().getType())) {
                throw new IOException("Node " + session.label + " did not send WELCOME");
            }
            return session;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** Uploads one chunk under its hash and requires an OK acknowledgement. */
    void uploadChunk(String hash, byte[] bytes) throws IOException {
        writer.send(new Message("CHUNK_UPLOAD", gson.toJson(new ChunkUploadRequest(hash))), bytes);

        Message header = readResponse();
        if (!"CHUNK_UPLOAD_ACK".equals(header.getType())) {
            throw new IOException("Unexpected response from " + label + ": " + header.getType() + " " + header.getData());
        }
        ChunkUploadAck ack = gson.fromJson(header.getData(), ChunkUploadAck.class);
        if (!"OK".equalsIgnoreCase(ack.status())) {
            throw new IOException("Chunk " + hash + " rejected by " + label + ": " + ack.message());
        }
    }

    /**
     * The node is reachable but couldn't supply a good copy of one chunk (it doesn't have it, or
     * its copy failed the integrity check). Other chunks on the same node may be fine.
     */
    static final class BadChunkException extends IOException {
        BadChunkException(String message) {
            super(message);
        }
    }

    /**
     * Downloads one chunk and verifies it.
     *
     * @throws BadChunkException if the node replies but has no good copy of this chunk
     * @throws IOException       if the connection to the node fails
     */
    byte[] downloadChunk(String hash) throws IOException {
        writer.send(new Message("CHUNK_DOWNLOAD", gson.toJson(new ChunkDownloadRequest(hash))), null);

        ReceivedMessage resp = reader.read();
        if (resp == null) {
            throw new IOException("Node " + label + " closed the connection");
        }
        Message header = resp.getHeader();
        if (!"CHUNK_DOWNLOAD_RESPONSE".equals(header.getType())) {
            throw new BadChunkException("Node " + label + " error: " + header.getData());
        }
        byte[] bytes = resp.getBody() == null ? new byte[0] : resp.getBody();
        // Never trust a single copy: verify every chunk against the hash it is addressed by
        if (!ChunkHashes.sha256Hex(bytes).equals(hash)) {
            throw new BadChunkException("Copy on " + label + " failed its integrity check");
        }
        return bytes;
    }

    private Message readResponse() throws IOException {
        ReceivedMessage resp = reader.read();
        if (resp == null) {
            throw new IOException("Node " + label + " closed the connection");
        }
        return resp.getHeader();
    }

    @Override
    public String toString() {
        return label;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
