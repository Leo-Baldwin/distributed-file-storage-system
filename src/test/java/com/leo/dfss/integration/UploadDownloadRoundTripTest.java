package com.leo.dfss.integration;

import com.leo.dfss.client.DownloadOrchestratorClient;
import com.leo.dfss.client.UploadOrchestratorClient;
import com.leo.dfss.coordinator.CoordinatorServer;
import com.leo.dfss.node.NodeServer;
import java.io.InputStream;
import java.security.MessageDigest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UploadDownloadRoundTripTest {

    @TempDir
    Path tempDir;

    private Thread coordinatorThread;
    private Thread nodeThread;

    private CoordinatorServer coordinator;
    private NodeServer node;

    @BeforeEach
    void startServers() throws Exception {
        // IMPORTANT: NodeServer currently expects Coordinator at localhost:9000 (hardcoded).
        coordinator = new CoordinatorServer(9000);
        coordinatorThread = new Thread(coordinator::start, "IT-Coordinator");
        coordinatorThread.setDaemon(true);
        coordinatorThread.start();

        // Node on 9100, chunks stored in temp dir
        Path nodeData = tempDir.resolve("node-data");
        node = new NodeServer(9100, nodeData);
        nodeThread = new Thread(node::start, "IT-Node");
        nodeThread.setDaemon(true);
        nodeThread.start();

        // Give servers a moment to bind ports + for node to register
        Thread.sleep(800);
    }

    @AfterEach
    void stopServers() {
        // NodeServer has a shutdown() already
        try { node.shutdown(); } catch (Exception ignored) {}

        // CoordinatorServer may not have a full shutdown() accept-loop stop yet.
        // For now, rely on daemon threads ending when tests finish.
        try { coordinator.shutdownAllConnections(); } catch (Exception ignored) {}
    }

    @Test
    void uploadThenDownload_fileIsIdentical() throws Exception {
        // Create an input file
        Path input = tempDir.resolve("input.bin");
        byte[] bytes = new byte[120_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        Files.write(input, bytes);

        // Upload (your uploadFile now returns fileId)
        String fileId = new UploadOrchestratorClient().uploadFile(input);
        assertNotNull(fileId);
        assertFalse(fileId.isBlank());

        // Download into a new directory
        Path outDir = tempDir.resolve("downloads");
        Files.createDirectories(outDir);

        new DownloadOrchestratorClient().downloadFile(fileId, outDir);

        // DownloadOrchestratorClient reconstructs using filename from metadata
        Path output = outDir.resolve(input.getFileName().toString());
        assertTrue(Files.exists(output), "Expected reconstructed file at " + output);

        // Compare hashes
        String inHash = sha256(input);
        String outHash = sha256(output);

        assertEquals(inHash, outHash, "Downloaded file differs from uploaded file");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }

        byte[] hash = md.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}