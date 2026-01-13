package com.leo.dfss;

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
                byte[] body = receivedMessage.getBody();

                if (header == null || header.getType() == null) {
                    writer.send(new Message("ERROR", "Missing message type."), null);
                    continue;
                }

                String type = header.getType();
                String data = header.getData();

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
            writer.send(new Message("ERROR", "FILES_INIT_REQUEST requires data: filename|size|chunkSize"), null);
            return;
        }

        String[] parts = data.split("\\|");
        if (parts.length != 3) {
            writer.send(new Message("ERROR", "Bad format. Use: filename|totalSizeBytes|chunkSizeBytes"), null);
            return;
        }

        String filename = parts[0];
        long totalSize;
        int chunkSize;
        try {
            totalSize = Long.parseLong(parts[1]);
            chunkSize = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            writer.send(new Message("ERROR", "totalSize and chunkSize must be numbers"), null);
            return;
        }

        FileMetadata meta = coordinator.initFileUpload(filename, totalSize, chunkSize);

        // Respond to client with file details
        String responseData = meta.getFileId() + "|" + meta.getTotalChunks() + "|" + meta.getChunkSizeBytes();
        writer.send(new Message("FILES_INIT_RESPONSE", responseData), null);;
    }

    private void handleFilesCommit(Message header, TcpMessageWriter writer) throws IOException {
        String fileId = header.getData();
        if (fileId == null || fileId.isBlank()) {
            writer.send(new Message("ERROR", "FILES_COMMIT requires data: fileId"), null);
            return;
        }

        boolean ok = coordinator.commitFile(fileId);
        if (!ok) {
            writer.send(new Message("ERROR", "Unknown fileId: " + fileId), null);
            return;
        }

        writer.send(new Message("FILES_COMMIT_ACK", "Committed fileId=" + fileId), null);
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
