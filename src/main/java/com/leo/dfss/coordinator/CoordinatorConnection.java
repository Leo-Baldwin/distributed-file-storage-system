package com.leo.dfss.coordinator;

import com.google.gson.Gson;
import com.leo.dfss.domain.FileMetadata;
import com.leo.dfss.protocol.*;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.Socket;

/**
 * Handles a single client connection in its own thread.
 * Reads framed messages and delegates operations to CoordinatorServer
 */
public class CoordinatorConnection extends Thread {

    private final Socket socket;
    private final int connectionId; // for logging/identification
    private final CoordinatorServer coordinator;

    private volatile boolean running = true;

    private static final Gson gson = new Gson();

    public CoordinatorConnection(Socket socket, int connectionId, CoordinatorServer coordinator) {
        this.socket = socket;
        this.connectionId = connectionId;
        this.coordinator = coordinator;
    }

    @Override
    public void run() {
        System.out.println("CoordinatorConnection " + connectionId + " started for " + socket.getRemoteSocketAddress());

        try {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new  TcpMessageWriter(socket.getOutputStream());

            writer.send(new Message("WELCOME", "Connection " + connectionId + " ready."), null);

            while (running) {
                ReceivedMessage receivedMessage = reader.read();
                if  (receivedMessage == null) {
                    System.out.println("[" + connectionId + "] Client disconnected.");
                    break;
                }

                Message header = receivedMessage.getHeader();

                if (header == null || header.getType() == null) {
                    writer.send(new Message("ERROR", "Missing message type."), null);
                    continue;
                }

                String type = header.getType();

                switch (type) {
                    case "PING":
                        writer.send(new Message("PONG", "Pong (connection " + connectionId + ")"), null);
                        break;
                    case "FILES_INIT_REQUEST":
                        handleFilesInit(header, writer);
                        break;
                    case "FILES_COMMIT":
                        handleFilesCommit(header, writer);
                        break;

                    case "QUIT":
                        writer.send(new Message("GOODBYE", "Closing connection"), null);
                        running = false; // break loop
                        break;

                    default:
                        writer.send(new Message("ERROR", "Unknown message type: " + type), null);
                        break;
                }
            }
        } catch (IOException e) {
            System.out.println("[" + connectionId + "] Connection error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Connection " + connectionId + " closed.");
    }

    private void handleFilesInit(Message header, TcpMessageWriter writer) throws IOException {
        String data = header.getData();

        if  (data == null) {
            writer.send(new Message("ERROR", "FILES_INIT_REQUEST requires JSON data"), null);
            return;
        }

        FilesInitRequest request;

        try {
            request = gson.fromJson(data, FilesInitRequest.class);
        } catch (Exception e) {
            writer.send(new Message("ERROR", "Invalid JSON format for FILES_INIT_REQUEST."), null);
            return;
        }

        if (request.getFilename() == null || request.getFilename().isBlank()) {
            writer.send(new Message("ERROR", "Missing file name."), null);
            return;
        }

        if (request.getTotalSizeBytes() <= 0) {
            writer.send(new Message("ERROR", "totalSizeBytes must be greater than 0."), null);
            return;
        }
        if (request.getChunkSizeBytes() <= 0) {
            writer.send(new Message("ERROR", "chunkSizeBytes must be greater than 0."), null);
            return;
        }

        // Call Coordinator to initialise file upload with metadata
        FileMetadata meta =
                coordinator.initFileUpload(
                        request.getFilename(),
                        request.getTotalSizeBytes(),
                        request.getChunkSizeBytes());

        // Respond to client with file details
        FilesInitResponse response = new FilesInitResponse();

        response.setFileId(meta.getFileId());
        response.setTotalChunks(meta.getTotalChunks());
        response.setChunkSizeBytes(meta.getChunkSizeBytes());

        writer.send(new Message(
                "FILES_INIT_RESPONSE", gson.toJson(response)),
                null);
    }

    private void handleFilesCommit(Message header, TcpMessageWriter writer) throws IOException {
        String data = header.getData();

        if (data == null || data.isBlank()) {
            writer.send(new Message(
                    "ERROR",
                    "FILES_COMMIT requires JSON data"),
                    null);
            return;
        }

        FilesCommitRequest request;
        try {
            request = gson.fromJson(data, FilesCommitRequest.class);
        } catch (Exception e) {
            writer.send(new Message(
                    "ERROR",
                    "Invalid JSON format for FILES_COMMIT."),
                    null);
            return;
        }

        if (request.getFileId() == null || request.getFileId().isBlank()) {
            writer.send(new Message(
                    "ERROR",
                    "fileId is required"),
                    null);
            return;
        }

        boolean ok = coordinator.commitFile(data);

        if (!ok) {
            writer.send(new Message(
                    "ERROR",
                    "Unknown fileId: " + request.getFileId()),
                    null);
            return;
        }

        FilesCommitAck ack = new FilesCommitAck();
        ack.setFileId(request.getFileId());
        ack.setStatus("OK");
        ack.setMessage("FIle commited successfully");

        writer.send(new Message(
                "FILES_COMMIT_ACK",
                gson.toJson(ack)),
                null);
    }

    public void shutdown() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
