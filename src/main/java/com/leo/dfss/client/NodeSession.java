package com.leo.dfss.client;

import com.google.gson.Gson;
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

    /** Uploads one chunk and requires an OK acknowledgement. */
    void uploadChunk(String fileId, int chunkIndex, byte[] bytes) throws IOException {
        ChunkUploadRequest req = new ChunkUploadRequest();
        req.setFileId(fileId);
        req.setChunkIndex(chunkIndex);
        req.setBodyLength(bytes.length);

        writer.send(new Message("CHUNK_UPLOAD", gson.toJson(req)), bytes);

        Message header = readResponse();
        if (!"CHUNK_UPLOAD_ACK".equals(header.getType())) {
            throw new IOException("Unexpected response from " + label + ": " + header.getType() + " " + header.getData());
        }
        ChunkUploadAck ack = gson.fromJson(header.getData(), ChunkUploadAck.class);
        if (!"OK".equalsIgnoreCase(ack.getStatus())) {
            throw new IOException("Chunk " + chunkIndex + " rejected by " + label + ": " + ack.getMessage());
        }
    }

    /** Downloads one chunk's bytes. */
    byte[] downloadChunk(String fileId, int chunkIndex) throws IOException {
        ChunkDownloadRequest req = new ChunkDownloadRequest();
        req.setFileId(fileId);
        req.setChunkIndex(chunkIndex);

        writer.send(new Message("CHUNK_DOWNLOAD", gson.toJson(req)), null);

        ReceivedMessage resp = reader.read();
        if (resp == null) {
            throw new IOException("Node " + label + " closed the connection");
        }
        Message header = resp.getHeader();
        if (!"CHUNK_DOWNLOAD_RESPONSE".equals(header.getType())) {
            throw new IOException("Node " + label + " error: " + header.getType() + " " + header.getData());
        }
        return resp.getBody() == null ? new byte[0] : resp.getBody();
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
