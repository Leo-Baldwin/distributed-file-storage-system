package com.leo.dfss.coordinator;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import com.leo.dfss.protocol.*;
import com.leo.dfss.transport.*;

import java.util.ArrayList;
import java.util.List;

import java.io.IOException;
import java.net.Socket;

/**
 * Per-connection handler for the CoordinatorServer.
 *
 * Responsibilities:
 * - Reads framed protocol messages from a single TCP client.
 * - Validates and deserialises request payloads (JSON header data).
 * - Delegates business logic to {@link CoordinatorServer}.
 * - Sends structured protocol responses back to the client.
 *
 * Threading model:
 * - One instance of this class runs per TCP connection.
 * - Extends {@link Thread} so each connection is handled independently.
 */
public class CoordinatorConnection extends Thread {

    private final Socket socket;
    private final int connectionId; // for logging/identification
    private final CoordinatorServer coordinator;

    private volatile boolean running = true;

    private static final Gson gson = new Gson();

    /**
     * Creates a new per-connection handler.
     *
     * @param socket        connected client socket
     * @param connectionId  unique identifier used for logging
     * @param coordinator   reference to the central CoordinatorServer
     */
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

            // Send initial welcome message so client knows connection is established
            writer.send(new Message("WELCOME", "Connection " + connectionId + " ready."), null);

            while (running) {
                // Read the next framed protocol message from the client
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

                // Call the appropriate handler method based on message type
                switch (type) {
                    case "FILES_INIT_REQUEST":
                        handleFilesInit(header, writer);
                        break;
                    case "FILES_COMMIT":
                        handleFilesCommit(header, writer);
                        break;
                    case "FILES_GET_REQUEST":
                        handleFilesGet(header, writer);
                        break;
                    case "NODE_REGISTER":
                        handleNodeRegister(header, writer);
                        break;
                    case "NODE_HEARTBEAT":
                        handleNodeHeartbeat(header, writer);
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
            // Ensure socket resources are released when the connection terminates
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Connection " + connectionId + " closed.");
    }

    /**
     * Handles FILES_INIT_REQUEST.
     * Validates request fields and creates a new in-memory file metadata record
     * via the Coordinator before returning upload location details.
     */
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

        // Backward compatibility: keep legacy single upload host/port populated
        response.setUploadHost(meta.getStorageHost());
        response.setUploadPort(meta.getStoragePort());

        // Replication: provide multiple upload targets (file-level replication)
        List<FilesInitResponse.NodeEndpoint> targets = new ArrayList<>();
        if (meta.getReplicaNodes() != null) {
            for (FileMetadata.NodeEndpoint ep : meta.getReplicaNodes()) {
                targets.add(new FilesInitResponse.NodeEndpoint(ep.getNodeId(), ep.getHost(), ep.getPort()));
            }
        }

        response.setUploadTargets(targets);

        writer.send(new Message(
                "FILES_INIT_RESPONSE", gson.toJson(response)),
                null);
    }

    /**
     * Handles FILES_COMMIT.
     * Marks the file as COMPLETE in the Coordinator if the fileId exists.
     */
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

        boolean ok = coordinator.commitFile(request.getFileId());

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
        ack.setMessage("File committed successfully");

        writer.send(new Message(
                "FILES_COMMIT_ACK",
                gson.toJson(ack)),
                null);
    }

    /**
     * Handles FILES_GET_REQUEST.
     * Returns file metadata and download location if the file exists and
     * has reached the COMPLETE lifecycle state.
     */
    private void handleFilesGet(Message header, TcpMessageWriter writer) throws IOException {
        String data = header.getData();

        if (data == null || data.isBlank()) {
            writer.send(new Message("ERROR", "FILES_GET_REQUEST requires JSON data"), null);
            return;
        }

        FilesGetRequest request;
        try {
            request = gson.fromJson(data, FilesGetRequest.class);
        } catch (Exception e) {
            writer.send(new Message("ERROR", "Invalid JSON format for FILES_GET_REQUEST"), null);
            return;
        }

        if (request.getFileId() == null || request.getFileId().isBlank()) {
            writer.send(new Message("ERROR", "fileId is required"), null);
            return;
        }

        FileMetadata metadata = coordinator.getFile(request.getFileId());

        if (metadata == null) {
            writer.send(new Message("ERROR", "Unknown fileId: " + request.getFileId()), null);
            return;
        }

        // Enforce lifecycle: only allow download if file has been fully committed
        if (metadata.getStatus() != FileMetadata.Status.COMPLETE) {
            writer.send(new Message("ERROR", "File is not committed yet"), null);
            return;
        }

        FilesGetResponse response = new FilesGetResponse();
        response.setFileId(metadata.getFileId());
        response.setFilename(metadata.getFileName());
        response.setTotalChunks(metadata.getTotalChunks());
        response.setChunkSizeBytes(metadata.getChunkSizeBytes());

        // Backward compatibility: keep legacy single download host/port populated
        response.setDownloadHost(metadata.getStorageHost());
        response.setDownloadPort(metadata.getStoragePort());

        // Replication: provide multiple download sources (file-level replication)
        List<FilesGetResponse.NodeEndpoint> sources = new ArrayList<>();
        if (metadata.getReplicaNodes() != null) {
            for (FileMetadata.NodeEndpoint ep : metadata.getReplicaNodes()) {
                sources.add(new FilesGetResponse.NodeEndpoint(ep.getNodeId(), ep.getHost(), ep.getPort()));
            }
        }
        response.setDownloadSources(sources);

        writer.send(new Message("FILES_GET_RESPONSE", gson.toJson(response)), null);
    }

    /**
     * Handles NODE_REGISTER.
     * Registers a storage node with the Coordinator's in-memory registry.
     */
    private void handleNodeRegister(Message header, TcpMessageWriter writer) throws java.io.IOException {
        String data = header.getData();
        if (data == null) {
            writer.send(new Message("ERROR", "NODE_REGISTER requires JSON data"), null);
            return;
        }

        NodeRegisterRequest req;
        try {
            req = gson.fromJson(data, NodeRegisterRequest.class);
        } catch (JsonSyntaxException e) {
            writer.send(new Message("ERROR", "Invalid JSON format for NODE_REGISTER"), null);
            return;
        }

        if (req.getNodeId() == null || req.getNodeId().isBlank()
                || req.getHost() == null || req.getHost().isBlank()
                || req.getPort() <= 0) {

            NodeRegisterAck ack = new NodeRegisterAck();
            ack.setStatus("ERROR");
            ack.setMessage("Missing/invalid fields (nodeId, host, port)");

            writer.send(new Message("NODE_REGISTER_ACK", gson.toJson(ack)), null);
            return;
        }

        boolean ok = coordinator.registerNode(
                req.getNodeId(),
                req.getHost(),
                req.getPort(),
                req.getCapacityBytes()
        );

        NodeRegisterAck ack = new NodeRegisterAck();
        if (ok) {
            ack.setStatus("OK");
            ack.setMessage("Node registered");
        } else {
            ack.setStatus("ERROR");
            ack.setMessage("Registration failed (invalid fields)");
        }

        writer.send(new Message("NODE_REGISTER_ACK", gson.toJson(ack)), null);
    }

    /**
     * Handles NODE_HEARTBEAT.
     * Updates the last-seen timestamp for a registered node.
     */
    private void handleNodeHeartbeat(Message header, TcpMessageWriter writer) throws java.io.IOException {
        String data = header.getData();
        if (data == null) {
            writer.send(new Message("ERROR", "NODE_HEARTBEAT requires JSON data"), null);
            return;
        }

        NodeHeartbeat hb;
        try {
            hb = gson.fromJson(data, NodeHeartbeat.class);
        } catch (JsonSyntaxException e) {
            writer.send(new Message("ERROR", "Invalid JSON format for NODE_HEARTBEAT"), null);
            return;
        }

        if (hb.getNodeId() == null || hb.getNodeId().isBlank()) {
            writer.send(new Message("ERROR", "Heartbeat missing nodeId"), null);
            return;
        }

        // If node didn't include a timestamp, you could default it; but we expect it.
        long ts = hb.getTimestampEpochMs();
        if (ts <= 0) {
            ts = System.currentTimeMillis();
        }

        boolean ok = coordinator.handleHeartbeat(hb.getNodeId(), ts);
        if (!ok) {
            writer.send(new Message("ERROR", "Unknown nodeId: " + hb.getNodeId()), null);
            return;
        }

        NodeHeartbeatAck ack = new NodeHeartbeatAck();
        ack.setStatus("OK");
        ack.setServerTimeEpochMs(System.currentTimeMillis());

        writer.send(new Message("NODE_HEARTBEAT_ACK", gson.toJson(ack)), null);
    }

    public void shutdown() {
        // Signal the run loop to terminate
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
