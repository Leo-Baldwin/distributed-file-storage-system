package com.leo.dfss.node;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.leo.dfss.protocol.*;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.Socket;

/**
 * Per-connection handler for a NodeServer.
 *
 * Responsibilities:
 * - Reads framed protocol messages from a single TCP client.
 * - Handles chunk upload and download requests.
 * - Interacts with {@link ChunkStore} for disk persistence.
 * - Sends structured protocol responses back to the client.
 *
 * Threading model:
 * - One instance of this class runs per TCP connection.
 * - Extends {@link Thread} so each connection is handled independently.
 */
public class NodeConnection extends Thread {

    private static final Gson gson = new Gson();

    private final Socket socket;
    private final int connectionId;
    private final ChunkStore chunkStore;

    private volatile boolean running = true;

    /**
     * Creates a new NodeConnection handler.
     *
     * @param socket        connected client socket
     * @param connectionId  identifier used for logging/debugging
     * @param chunkStore    storage component responsible for chunk persistence
     */
    public NodeConnection(Socket socket, int connectionId, ChunkStore chunkStore) {
        this.socket = socket;
        this.connectionId = connectionId;
        this.chunkStore = chunkStore;
    }

    @Override
    public void run() {

        System.out.println("NodeConnection thread " + connectionId + " started for " + socket.getRemoteSocketAddress());

        try {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Greet client with initial welcome message
            writer.send(new Message("WELCOME", "Node connection " + connectionId + " configured."), null);

            while (running) {
                // Read the next framed protocol message from the client
                ReceivedMessage received = reader.read();
                if (received == null) {
                    System.out.println("Client disconnected.");
                    break;
                }

                Message header = received.getHeader();
                byte[] body = received.getBody();

                if (header == null || header.getType() == null) {
                    writer.send(new Message("ERROR", "Missing message type"), null);
                    continue;
                }

                String type = header.getType();

                // Dispatch to appropriate handler based on message type
                switch (type) {
                    case "PING":
                        writer.send(new Message("PONG", "Pong (node connection: " + connectionId + ")"), null);
                        break;

                    case "CHUNK_UPLOAD":
                        handleChunkUpload(header, body, writer);
                        break;

                    case "CHUNK_DOWNLOAD":
                        handleChunkDownload(header, body, writer);
                        break;

                    case "QUIT":
                        writer.send(new Message("GOODBYE", "Closing node connection"), null);
                        running = false;
                        break;

                    default:
                        writer.send(new Message("ERROR", "Unknown message type: " + type), null);
                        break;
                }
            }
        } catch (IOException e) {
            System.out.println("Node connection error: " + e.getMessage());
        } finally {
            // Ensure socket resources are released when connection terminates
            try {
                socket.close();
            } catch (IOException ignore) {
            }
            System.out.println("NodeConnection " + connectionId + " closed.");
        }
    }

    /**
     * Handles CHUNK_UPLOAD messages.
     * Validates metadata and writes the received chunk bytes to disk via ChunkStore.
     */
    private void handleChunkUpload(Message header, byte[] body, TcpMessageWriter writer) throws IOException {
        String data = header.getData();
        if (data == null) {
            writer.send(new Message("ERROR", "Missing message data"), null);
            return;
        }

        ChunkUploadRequest request;

        try {
            request = gson.fromJson(data, ChunkUploadRequest.class);
        } catch (JsonSyntaxException e) {
            writer.send(new Message("ERROR", "Invalid data format for CHUNK_UPLOAD"), null);
            return;
        }

        if (request.getFileId() == null || request.getFileId().isBlank()) {
            writer.send(new Message("ERROR", "CHUNK_UPLOAD missing fileId"), null);
            return;
        }

        if (request.getChunkIndex() < 0) {
            writer.send(new Message("ERROR", "Invalid chunkIndex"), null);
            return;
        }

        if (request.getBodyLength() <= 0) {
            writer.send(new Message("ERROR", "Invalid bodyLength"), null);
            return;
        }

        // Validate that binary body matches declared bodyLength in header
        if (body == null || body.length != request.getBodyLength()) {
            writer.send(new Message("ERROR", "Body length does not match length specified in header"), null);
            return;
        }

        // Persist chunk bytes to disk using ChunkStore abstraction
        try {
            chunkStore.writeChunk(request.getFileId(), request.getChunkIndex(), body);
        } catch (Exception e) {
            // Acknowledge chunk upload error
            ChunkUploadAck ack = new ChunkUploadAck();
            ack.setFileId(request.getFileId());
            ack.setChunkIndex(request.getChunkIndex());
            ack.setStatus("ERROR");
            ack.setMessage("Failed to write chunk");

            writer.send(new Message("CHUNK_UPLOAD_ACK", gson.toJson(ack)), null);
            return;
        }

        // Acknowledge successful chunk persistence
        ChunkUploadAck ack = new ChunkUploadAck();
        ack.setFileId(request.getFileId());
        ack.setChunkIndex(request.getChunkIndex());
        ack.setStatus("OK");
        ack.setMessage("Chunk uploaded successfully");

        writer.send(new Message("CHUNK_UPLOAD_ACK", gson.toJson(ack)), null);
    }

    /**
     * Handles CHUNK_DOWNLOAD messages.
     * Reads the requested chunk from disk and returns it to the client.
     */
    private void handleChunkDownload(Message header, byte[] body, TcpMessageWriter writer) throws IOException {
        String data = header.getData();

        if (data == null || data.isBlank()) {
            writer.send(new Message("ERROR", "CHUNK_DOWNLOAD requires JSON data"), null);
            return;
        }

        ChunkDownloadRequest request;

        try {
            request = gson.fromJson(data, ChunkDownloadRequest.class);
        } catch (Exception e) {
            writer.send(new Message("ERROR", "Invalid JSON for CHUNK_DOWNLOAD"), null);
            return;
        }

        // Read requested chunk bytes from disk
        byte[] chunkBytes;
        try {
            chunkBytes = chunkStore.readChunk(
                    request.getFileId(),
                    request.getChunkIndex()
            );
        } catch (Exception e) {
            writer.send(new Message("ERROR", "Failed to read chunk: " + e.getMessage()), null);
            return;
        }

        if (chunkBytes == null) {
            writer.send(new Message("ERROR", "Chunk not found"), null);
            return;
        }

        ChunkDownloadResponse response = new ChunkDownloadResponse();
        response.setStatus("OK");
        response.setMessage("Chunk read");
        response.setBodyLength(chunkBytes.length);

        // Send metadata header + raw binary chunk bytes back to client
        writer.send(new Message(
                "CHUNK_DOWNLOAD_RESPONSE",
                gson.toJson(response)),
                chunkBytes);
    }

    /**
     * Signals this connection thread to terminate and closes the underlying socket.
     */
    public void shutdown() {
        this.running = false;
        try {
            socket.close();
        } catch (IOException ignore) {
        }
    }
}
