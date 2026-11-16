package com.leo.dfss.integration;

import com.leo.dfss.client.DownloadOrchestratorClient;
import com.leo.dfss.client.UploadOrchestratorClient;
import com.leo.dfss.config.ClientConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class UploadDownloadRoundTripTest {

    @TempDir
    Path tempDir;

    private TestCluster cluster;

    @BeforeEach
    void startCluster() throws Exception {
        cluster = TestCluster.start(tempDir.resolve("cluster"), 3);
    }

    @AfterEach
    void stopCluster() {
        if (cluster != null) cluster.close();
    }

    @Test
    void uploadThenDownload_fileIsIdentical() throws Exception {
        // Small chunks so this file spans many chunks (and a partial last chunk)
        ClientConfig client = cluster.client().withChunkSize(4096);
        Path input = writePatternFile("input.bin", 120_000);

        assertRoundTrip(client, input);
    }

    @Test
    void largeFile_withDefaultChunkSize_roundTrips() throws Exception {
        Path input = writeRandomFile("large.bin", 50 * 1024 * 1024 + 123); // 50 MiB plus a partial chunk

        assertRoundTrip(cluster.client(), input);
    }

    @Test
    void download_survivesNodeFailure() throws Exception {
        ClientConfig client = cluster.client().withChunkSize(64 * 1024);
        Path input = writeRandomFile("failover.bin", 1_000_000);

        String fileId = new UploadOrchestratorClient(client).uploadFile(input);

        // Every chunk is on all 3 nodes, so losing any two must not matter
        cluster.nodes.get(0).shutdown();
        cluster.nodes.get(1).shutdown();

        Path outDir = Files.createDirectories(tempDir.resolve("downloads"));
        new DownloadOrchestratorClient(client).downloadFile(fileId, outDir);

        assertEquals(sha256(input), sha256(outDir.resolve(input.getFileName())));
    }

    @Test
    void upload_withNoNodes_failsWithClearError() throws Exception {
        cluster.close();
        try (TestCluster empty = TestCluster.start(tempDir.resolve("empty"), 0)) {
            Path input = writePatternFile("orphan.bin", 10);
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> new UploadOrchestratorClient(empty.client()).uploadFile(input));
            assertTrue(fullMessage(e).contains("No active storage nodes"), fullMessage(e));
        }
    }

    private void assertRoundTrip(ClientConfig client, Path input) throws Exception {
        String fileId = new UploadOrchestratorClient(client).uploadFile(input);
        assertNotNull(fileId);
        assertFalse(fileId.isBlank());

        Path outDir = Files.createDirectories(tempDir.resolve("downloads"));
        new DownloadOrchestratorClient(client).downloadFile(fileId, outDir);

        Path output = outDir.resolve(input.getFileName().toString());
        assertTrue(Files.exists(output), "Expected reconstructed file at " + output);
        assertEquals(sha256(input), sha256(output), "Downloaded file differs from uploaded file");
    }

    private Path writePatternFile(String name, int size) throws Exception {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        return Files.write(tempDir.resolve(name), bytes);
    }

    private Path writeRandomFile(String name, int size) throws Exception {
        byte[] bytes = new byte[size];
        new Random(7).nextBytes(bytes);
        return Files.write(tempDir.resolve(name), bytes);
    }

    private static String fullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) sb.append(t.getMessage()).append(" | ");
        return sb.toString();
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
        return HexFormat.of().formatHex(md.digest());
    }
}
