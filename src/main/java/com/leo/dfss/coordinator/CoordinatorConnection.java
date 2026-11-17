package com.leo.dfss.coordinator;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.leo.dfss.common.ChunkHashes;
import com.leo.dfss.protocol.*;
import com.leo.dfss.transport.ReceivedMessage;
import com.leo.dfss.transport.TcpMessageReader;
import com.leo.dfss.transport.TcpMessageWriter;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-connection handler for the CoordinatorServer.
 *
 * Responsibilities:
 * - Reads framed protocol messages from a single TCP client (an upload/download client or a node).
 * - Validates and deserialises request payloads. Everything from the network is untrusted:
 *   bad input gets an ERROR reply, never an uncaught exception.
 * - Delegates business logic to {@link CoordinatorServer}.
 *
 * Threading model: one instance (thread) per TCP connection.
 */
public class CoordinatorConnection extends Thread {

    private static final Gson gson = new Gson();

    private final Socket socket;
    private final int connectionId;
    private final CoordinatorServer coordinator;

    private volatile boolean running = true;

    public CoordinatorConnection(Socket socket, int connectionId, CoordinatorServer coordinator) {
        this.socket = socket;
        this.connectionId = connectionId;
        this.coordinator = coordinator;
    }

    @Override
    public void run() {
        try {
            TcpMessageReader reader = new TcpMessageReader(socket.getInputStream());
            TcpMessageWriter writer = new TcpMessageWriter(socket.getOutputStream());

            // Send initial welcome message so client knows connection is established
            writer.send(new Message("WELCOME", "Connection " + connectionId + " ready."), null);

            while (running) {
                ReceivedMessage received = reader.read();
                if (received == null) {
                    break; // client disconnected
                }

                Message header = received.getHeader();
                if (header.getType() == null) {
                    writer.send(new Message("ERROR", "Missing message type."), null);
                    continue;
                }

                try {
                    switch (header.getType()) {
                        case "FILES_INIT_REQUEST" -> handleFilesInit(header, writer);
                        case "FILES_COMMIT" -> handleFilesCommit(header, received.getBody(), writer);
                        case "FILES_GET_REQUEST" -> handleFilesGet(header, writer);
                        case "NODE_REGISTER" -> handleNodeRegister(header, writer);
                        case "NODE_HEARTBEAT" -> handleNodeHeartbeat(header, writer);
                        case "NODE_CHUNKS_ADDED" -> handleChunksAdded(header, received.getBody(), writer);
                        case "QUIT" -> {
                            writer.send(new Message("GOODBYE", "Closing connection"), null);
                            running = false;
                        }
                        default -> writer.send(new Message("ERROR", "Unknown message type: " + header.getType()), null);
                    }
                } catch (JsonSyntaxException | IllegalArgumentException e) {
                    writer.send(new Message("ERROR", "Invalid " + header.getType() + ": " + e.getMessage()), null);
                }
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("[" + connectionId + "] Connection error: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            // e.g. a metadata store failure: log it and drop this connection, not the whole server
            System.out.println("[" + connectionId + "] Internal error: " + e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Parses the JSON payload of a message, rejecting a missing payload. */
    private static <T> T parse(Message header, Class<T> type) {
        if (header.getData() == null || header.getData().isBlank()) {
            throw new IllegalArgumentException("missing JSON data");
        }
        T value = gson.fromJson(header.getData(), type);
        if (value == null) {
            throw new IllegalArgumentException("missing JSON data");
        }
        return value;
    }

    /**
     * Handles FILES_INIT_REQUEST: validates the request, creates the file record and replies with
     * the nodes to upload to.
     */
    private void handleFilesInit(Message header, TcpMessageWriter writer) throws IOException {
        FilesInitRequest request = parse(header, FilesInitRequest.class);

        String filename = request.filename();
        if (filename == null || filename.isBlank()) {
            writer.send(new Message("ERROR", "Missing file name."), null);
            return;
        }
        // A plain name only: it is later used as a path by downloading clients
        if (filename.equals(".") || filename.equals("..") || filename.contains("/")
                || filename.contains("\\") || filename.indexOf('\0') >= 0) {
            writer.send(new Message("ERROR", "File name must not contain path separators."), null);
            return;
        }
        if (request.totalSizeBytes() < 0) {
            writer.send(new Message("ERROR", "totalSizeBytes must not be negative."), null);
            return;
        }
        if (request.chunkSizeBytes() <= 0 || request.chunkSizeBytes() > TcpMessageReader.MAX_BODY_BYTES) {
            writer.send(new Message("ERROR",
                    "chunkSizeBytes must be between 1 and " + TcpMessageReader.MAX_BODY_BYTES + "."), null);
            return;
        }

        CoordinatorServer.UploadPlan plan;
        try {
            plan = coordinator.initFileUpload(filename, request.totalSizeBytes(), request.chunkSizeBytes());
        } catch (IllegalStateException e) {
            // e.g. no storage nodes are UP
            writer.send(new Message("ERROR", e.getMessage()), null);
            return;
        }

        List<NodeEndpoint> targets = plan.targets().stream()
                .map(n -> new NodeEndpoint(n.getNodeId(), n.getHost(), n.getPort()))
                .toList();
        FilesInitResponse response = new FilesInitResponse(plan.file().fileId(),
                (int) plan.file().totalChunks(), plan.file().chunkSizeBytes(), targets, plan.warning());
        writer.send(new Message("FILES_INIT_RESPONSE", gson.toJson(response)), null);
    }

    /**
     * Handles FILES_COMMIT. The body carries the file's ordered chunk hashes.
     */
    private void handleFilesCommit(Message header, byte[] body, TcpMessageWriter writer) throws IOException {
        FilesCommitRequest request = parse(header, FilesCommitRequest.class);
        if (request.fileId() == null || request.fileId().isBlank()) {
            writer.send(new Message("ERROR", "fileId is required"), null);
            return;
        }

        List<String> hashes = ChunkHashes.unpack(body);
        CoordinatorServer.CommitOutcome outcome = coordinator.commitFile(request.fileId(), hashes);

        FilesCommitAck ack = new FilesCommitAck(request.fileId(), outcome.status(), outcome.message(),
                outcome.missingChunks());
        writer.send(new Message("FILES_COMMIT_ACK", gson.toJson(ack)), null);
    }

    /**
     * Handles FILES_GET_REQUEST: returns the file's metadata plus, for every chunk, which UP nodes
     * hold it. Only committed files can be downloaded.
     */
    private void handleFilesGet(Message header, TcpMessageWriter writer) throws IOException {
        FilesGetRequest request = parse(header, FilesGetRequest.class);
        if (request.fileId() == null || request.fileId().isBlank()) {
            writer.send(new Message("ERROR", "fileId is required"), null);
            return;
        }

        Optional<FileMetadata> file = coordinator.getFile(request.fileId());
        if (file.isEmpty()) {
            writer.send(new Message("ERROR", "Unknown fileId: " + request.fileId()), null);
            return;
        }
        if (file.get().status() != FileMetadata.Status.COMPLETE) {
            writer.send(new Message("ERROR", "File is not committed yet"), null);
            return;
        }

        // Build a compact node list and, per chunk, indexes into it (UP nodes only)
        List<NodeEndpoint> nodeList = new ArrayList<>();
        Map<String, Integer> nodeIndex = new HashMap<>();
        List<ChunkPlacements.Placement> placements = new ArrayList<>();
        ChunkLocations locations = coordinator.getChunkLocations();

        for (String hash : coordinator.getFileChunks(request.fileId())) {
            List<Integer> indexes = new ArrayList<>();
            for (String nodeId : locations.nodesFor(hash)) {
                if (indexes.size() == CoordinatorServer.MAX_HOLDERS_LISTED) {
                    break;
                }
                NodeInfo node = coordinator.getNodes().get(nodeId);
                if (node == null || node.getStatus() != NodeInfo.Status.UP) {
                    continue;
                }
                Integer idx = nodeIndex.get(nodeId);
                if (idx == null) {
                    idx = nodeList.size();
                    nodeIndex.put(nodeId, idx);
                    nodeList.add(new NodeEndpoint(nodeId, node.getHost(), node.getPort()));
                }
                indexes.add(idx);
            }
            placements.add(new ChunkPlacements.Placement(hash, indexes.stream().mapToInt(Integer::intValue).toArray()));
        }

        FileMetadata f = file.get();
        FilesGetResponse response = new FilesGetResponse(f.fileId(), f.fileName(), f.totalSizeBytes(),
                (int) f.totalChunks(), f.chunkSizeBytes(), nodeList);
        writer.send(new Message("FILES_GET_RESPONSE", gson.toJson(response)), ChunkPlacements.encode(placements));
    }

    /**
     * Handles NODE_REGISTER. If the node doesn't say how clients should reach it, the address it
     * connected from is used.
     */
    private void handleNodeRegister(Message header, TcpMessageWriter writer) throws IOException {
        NodeRegisterRequest req = parse(header, NodeRegisterRequest.class);

        NodeRegisterAck ack = new NodeRegisterAck();
        if (req.getNodeId() == null || req.getNodeId().isBlank() || req.getPort() <= 0) {
            ack.setStatus("ERROR");
            ack.setMessage("Missing/invalid fields (nodeId, port)");
            writer.send(new Message("NODE_REGISTER_ACK", gson.toJson(ack)), null);
            return;
        }

        String host = req.getHost();
        if (host == null || host.isBlank()) {
            host = socket.getInetAddress().getHostAddress();
        }

        String sessionId = coordinator.registerNode(req.getNodeId(), host, req.getPort(),
                req.getCapacityBytes(), req.getFailureDomain());

        ack.setStatus(sessionId != null ? "OK" : "ERROR");
        ack.setMessage(sessionId != null ? "Node registered" : "Registration failed (invalid fields)");
        ack.setSessionId(sessionId);
        writer.send(new Message("NODE_REGISTER_ACK", gson.toJson(ack)), null);
    }

    /**
     * Handles NODE_HEARTBEAT. An unknown node gets an ERROR, which makes it re-register.
     */
    private void handleNodeHeartbeat(Message header, TcpMessageWriter writer) throws IOException {
        NodeHeartbeat hb = parse(header, NodeHeartbeat.class);
        if (hb.getNodeId() == null || hb.getNodeId().isBlank()) {
            writer.send(new Message("ERROR", "Heartbeat missing nodeId"), null);
            return;
        }

        long ts = hb.getTimestampEpochMs() > 0 ? hb.getTimestampEpochMs() : System.currentTimeMillis();
        if (!coordinator.handleHeartbeat(hb.getNodeId(), hb.getSessionId(), ts, hb.getFreeBytes())) {
            writer.send(new Message("ERROR", "Unknown node or stale session: " + hb.getNodeId()), null);
            return;
        }

        NodeHeartbeatAck ack = new NodeHeartbeatAck();
        ack.setStatus("OK");
        ack.setServerTimeEpochMs(System.currentTimeMillis());
        writer.send(new Message("NODE_HEARTBEAT_ACK", gson.toJson(ack)), null);
    }

    /**
     * Handles NODE_CHUNKS_ADDED: the body lists chunk hashes the node now holds.
     */
    private void handleChunksAdded(Message header, byte[] body, TcpMessageWriter writer) throws IOException {
        NodeChunksAdded msg = parse(header, NodeChunksAdded.class);
        List<String> hashes = ChunkHashes.unpack(body);
        if (!coordinator.chunksAdded(msg.nodeId(), msg.sessionId(), hashes)) {
            writer.send(new Message("ERROR", "Unknown node or stale session: " + msg.nodeId()), null);
            return;
        }
        writer.send(new Message("NODE_CHUNKS_ACK", String.valueOf(hashes.size())), null);
    }

    public void shutdown() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
