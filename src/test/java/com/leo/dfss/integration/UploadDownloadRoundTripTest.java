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
    private Thread nodeThread1;
    private Thread nodeThread2;
    private Thread nodeThread3;

    private CoordinatorServer coordinator;
    private NodeServer node1;
    private NodeServer node2;
    private NodeServer node3;

    @BeforeEach
    void startServers() throws Exception {
        // NodeServer currently expects Coordinator at localhost:9000 (hardcoded).
        coordinator = new CoordinatorServer(9000);
        coordinatorThread = new Thread(coordinator::start, "IT-Coordinator");
        coordinatorThread.setDaemon(true);
        coordinatorThread.start();

        // Nodes on 9100/9200/9300, chunks stored in separate temp directories
        Path nodeData1 = tempDir.resolve("node-data-1");
        Path nodeData2 = tempDir.resolve("node-data-2");
        Path nodeData3 = tempDir.resolve("node-data-3");

        node1 = new NodeServer(9100, nodeData1);
        nodeThread1 = new Thread(node1::start, "IT-Node-1");
        nodeThread1.setDaemon(true);
        nodeThread1.start();

        node2 = new NodeServer(9200, nodeData2);
        nodeThread2 = new Thread(node2::start, "IT-Node-2");
        nodeThread2.setDaemon(true);
        nodeThread2.start();

        node3 = new NodeServer(9300, nodeData3);
        nodeThread3 = new Thread(node3::start, "IT-Node-3");
        nodeThread3.setDaemon(true);
        nodeThread3.start();

        // Give servers a moment to bind ports + for node to register
        Thread.sleep(800);
    }

    @AfterEach
    void stopServers() {
        // NodeServer has a shutdown() already
        try { if (node1 != null) node1.shutdown(); } catch (Exception ignored) {}
        try { if (node2 != null) node2.shutdown(); } catch (Exception ignored) {}
        try { if (node3 != null) node3.shutdown(); } catch (Exception ignored) {}

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

        // Upload
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