package com.leo.dfss.node;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.leo.dfss.domain.ChunkStore;
import com.leo.dfss.protocol.ChunkUploadAck;
import com.leo.dfss.protocol.ChunkUploadRequest;
import com.leo.dfss.protocol.Message;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.Socket;

public class NodeConnection extends Thread {

    private static final Gson gson = new Gson();

    private final Socket socket;
    private final int connectionId;
    private final ChunkStore chunkStore;

    private volatile boolean running = true;

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

            // Great client
            writer.send(new Message("WELCOME", "Node connection " + connectionId + "configured."), null);

            while (running) {
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

                switch (type) {
                    case "PING":
                        writer.send(new Message("PONG", "Pong (node connection: " + connectionId + ")"), null);
                        break;

                    case "CHUNK_UPLOAD":
                        handleChunkUpload(header, body, writer);
                        break;

                    case "QUIT":
                        writer.send(new Message("GOODBYE", "Closing node connection"), null);
                        break;

                    default:
                        writer.send(new Message("ERROR", "Unknown message type: " + type), null);
                        break;
                }
            }
        } catch (IOException e) {
            System.out.println("Node connection error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignore) {
                System.out.println("NodeConnection " + connectionId + " closed.");
            }
        }
    }

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
            writer.send(new Message("ERROR", "CHUNK_UPLOAD missing fieldId"), null);
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

        if (body == null || body.length != request.getBodyLength()) {
            writer.send(new Message("ERROR", "Body length does not match length specified in header"), null);
            return;
        }

        // Write chunk bytes to disk
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

        // Acknowledge chunk upload success
        ChunkUploadAck ack = new ChunkUploadAck();
        ack.setFileId(request.getFileId());
        ack.setChunkIndex(request.getChunkIndex());
        ack.setStatus("OK");
        ack.setMessage("Chunk uploaded successfully");

        writer.send(new Message("CHUNK_UPLOAD_ACK", gson.toJson(ack)), null);
    }

    public void shutdown() {
        this.running = false;
        try {
            socket.close();
        } catch (IOException ignore) {
        }
    }
}
