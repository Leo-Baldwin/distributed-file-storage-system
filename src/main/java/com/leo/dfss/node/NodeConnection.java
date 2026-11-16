package com.leo.dfss.node;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.leo.dfss.common.ChunkHashes;
import com.leo.dfss.protocol.*;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.Socket;
import java.util.function.Consumer;

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
    private final Consumer<String> onChunkStored; // called with the hash of each newly stored chunk

    private volatile boolean running = true;

    /**
     * Creates a new NodeConnection handler.
     *
     * @param socket        connected client socket
     * @param connectionId  identifier used for logging/debugging
     * @param chunkStore    storage component responsible for chunk persistence
     * @param onChunkStored called with the hash of every chunk newly written to disk
     */
    public NodeConnection(Socket socket, int connectionId, ChunkStore chunkStore, Consumer<String> onChunkStored) {
        this.socket = socket;
        this.connectionId = connectionId;
        this.chunkStore = chunkStore;
        this.onChunkStored = onChunkStored;
    }

    @Override
    public void run() {

        try {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Greet client with initial welcome message
            writer.send(new Message("WELCOME", "Node connection " + connectionId + " configured."), null);

            while (running) {
                // Read the next framed protocol message from the client
                ReceivedMessage received = reader.read();
                if (received == null) {
                    break; // client disconnected
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
                        handleChunkDownload(header, writer);
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
        }
    }

    /**
     * Handles CHUNK_UPLOAD: stores the body under its hash after checking the bytes match it.
     */
    private void handleChunkUpload(Message header, byte[] body, TcpMessageWriter writer) throws IOException {
        ChunkUploadRequest request;
        try {
            request = gson.fromJson(header.getData(), ChunkUploadRequest.class);
        } catch (JsonSyntaxException e) {
            writer.send(new Message("ERROR", "Invalid data format for CHUNK_UPLOAD"), null);
            return;
        }
        if (request == null || !ChunkHashes.isValid(request.hash())) {
            writer.send(new Message("ERROR", "CHUNK_UPLOAD requires a valid chunk hash"), null);
            return;
        }
        String hash = request.hash();
        if (body == null) {
            sendUploadAck(writer, hash, "ERROR", "Missing chunk body");
            return;
        }

        try {
            if (chunkStore.put(hash, body)) {
                onChunkStored.accept(hash); // tell the Coordinator this node now holds it
            }
        } catch (IllegalArgumentException e) {
            sendUploadAck(writer, hash, "ERROR", e.getMessage()); // e.g. bytes don't match the hash
            return;
        } catch (IOException e) {
            sendUploadAck(writer, hash, "ERROR", "Failed to write chunk");
            return;
        }

        sendUploadAck(writer, hash, "OK", "Stored");
    }

    private void sendUploadAck(TcpMessageWriter writer, String hash, String status, String message) throws IOException {
        writer.send(new Message("CHUNK_UPLOAD_ACK", gson.toJson(new ChunkUploadAck(hash, status, message))), null);
    }

    /**
     * Handles CHUNK_DOWNLOAD: returns the chunk with the requested hash.
     */
    private void handleChunkDownload(Message header, TcpMessageWriter writer) throws IOException {
        ChunkDownloadRequest request;
        try {
            request = gson.fromJson(header.getData(), ChunkDownloadRequest.class);
        } catch (JsonSyntaxException e) {
            writer.send(new Message("ERROR", "Invalid JSON for CHUNK_DOWNLOAD"), null);
            return;
        }
        if (request == null || !ChunkHashes.isValid(request.hash())) {
            writer.send(new Message("ERROR", "CHUNK_DOWNLOAD requires a valid chunk hash"), null);
            return;
        }

        byte[] chunkBytes;
        try {
            chunkBytes = chunkStore.get(request.hash());
        } catch (IOException e) {
            writer.send(new Message("ERROR", "Failed to read chunk: " + e.getMessage()), null);
            return;
        }
        if (chunkBytes == null) {
            writer.send(new Message("ERROR", "Chunk not found: " + request.hash()), null);
            return;
        }

        ChunkDownloadResponse response = new ChunkDownloadResponse(request.hash(), "OK", "Chunk read");
        writer.send(new Message("CHUNK_DOWNLOAD_RESPONSE", gson.toJson(response)), chunkBytes);
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
