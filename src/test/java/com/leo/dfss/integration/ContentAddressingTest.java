package com.leo.dfss.integration;

import com.leo.dfss.client.DownloadOrchestratorClient;
import com.leo.dfss.client.UploadOrchestratorClient;
import com.leo.dfss.config.ClientConfig;
import com.leo.dfss.node.NodeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 behaviour: chunks addressed by hash, metadata that survives restarts, integrity checks.
 */
class ContentAddressingTest {

    @TempDir
    Path tempDir;

    private TestCluster cluster;

    @AfterEach
    void stop() {
        if (cluster != null) cluster.close();
    }

    @Test
    void uploadingTheSameDataTwice_storesItOnce() throws Exception {
        cluster = TestCluster.start(tempDir.resolve("cluster"), 2);
        ClientConfig client = cluster.client().withChunkSize(64 * 1024);
        Path original = randomFile("a.bin", 1_000_000, 1);
        Path copy = Files.copy(original, tempDir.resolve("b.bin"));

        new UploadOrchestratorClient(client).uploadFile(original);
        java.util.Map<Path, java.nio.file.attribute.FileTime> before = modifiedTimes(cluster.nodes.get(0));
        String secondId = new UploadOrchestratorClient(client).uploadFile(copy);

        // Content-addressed paths can't create duplicate files, so counting files proves nothing;
        // check that no stored chunk was rewritten at all
        assertEquals(before, modifiedTimes(cluster.nodes.get(0)), "identical content must not be written again");
        Path out = new DownloadOrchestratorClient(client).downloadFile(secondId, tempDir.resolve("out"));
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(out));
    }

    @Test
    void corruptedCopy_isDetected_andAnotherCopyIsUsed() throws Exception {
        cluster = TestCluster.start(tempDir.resolve("cluster"), 2);
        ClientConfig client = cluster.client().withChunkSize(64 * 1024);
        Path input = randomFile("c.bin", 300_000, 2);
        String fileId = new UploadOrchestratorClient(client).uploadFile(input);

        // Silently corrupt alternate chunks on each node, so every chunk keeps exactly one good copy
        // and, whichever node the client tries first, some chunks are read from a corrupt copy first
        List<Path> onFirst = sorted(chunkFiles(cluster.nodes.get(0)));
        List<Path> onSecond = sorted(chunkFiles(cluster.nodes.get(1)));
        assertEquals(onFirst.size(), onSecond.size());
        for (int i = 0; i < onFirst.size(); i++) {
            corrupt(i % 2 == 0 ? onFirst.get(i) : onSecond.get(i));
        }

        Path out = new DownloadOrchestratorClient(client).downloadFile(fileId, tempDir.resolve("out"));
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(out));
    }

    @Test
    void filesSurviveACoordinatorRestart() throws Exception {
        cluster = TestCluster.start(tempDir.resolve("cluster"), 3);
        ClientConfig client = cluster.client().withChunkSize(32 * 1024);
        Path input = randomFile("keep.bin", 500_000, 3);
        String fileId = new UploadOrchestratorClient(client).uploadFile(input);

        cluster.restartCoordinator();
        // Nodes notice, re-register and re-send their inventories
        cluster.awaitActiveNodes(3);

        // Straight away: the client waits while nodes re-send their inventories
        Path out = new DownloadOrchestratorClient(client).downloadFile(fileId, tempDir.resolve("out"));
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(out));
    }

    @Test
    void duplicateRegistration_doesNotLeaveANodesChunksInvisible() throws Exception {
        cluster = TestCluster.start(tempDir.resolve("cluster"), 1);
        ClientConfig client = cluster.client().withChunkSize(64 * 1024);
        Path input = randomFile("d.bin", 200_000, 4);
        String fileId = new UploadOrchestratorClient(client).uploadFile(input);
        NodeServer node = cluster.nodes.get(0);
        int held = cluster.coordinator.getChunkLocations().chunkCount(node.getNodeId());
        assertTrue(held > 0);

        // Something else registers with the same node ID (copied data dir, stray process...):
        // the Coordinator forgets the node's chunks and records a bogus address
        cluster.coordinator.registerNode(node.getNodeId(), "127.0.0.1", 1, 0, "elsewhere");
        assertEquals(0, cluster.coordinator.getChunkLocations().chunkCount(node.getNodeId()));

        // The real node's next heartbeat carries a stale session, so it re-registers and
        // re-sends its inventory
        TestCluster.awaitTrue(() -> cluster.coordinator.getChunkLocations().chunkCount(node.getNodeId()) == held
                        && cluster.coordinator.getNodes().get(node.getNodeId()).getPort() == node.getPort(),
                () -> "node did not recover its registration");

        Path out = new DownloadOrchestratorClient(client).downloadFile(fileId, tempDir.resolve("out"));
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(out));
    }

    @Test
    void emptyFile_roundTrips() throws Exception {
        cluster = TestCluster.start(tempDir.resolve("cluster"), 1);
        Path empty = Files.write(tempDir.resolve("empty.txt"), new byte[0]);

        String fileId = new UploadOrchestratorClient(cluster.client()).uploadFile(empty);
        Path out = new DownloadOrchestratorClient(cluster.client()).downloadFile(fileId, tempDir.resolve("out"));

        assertEquals(0, Files.size(out));
    }

    private Path randomFile(String name, int size, long seed) throws IOException {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return Files.write(tempDir.resolve(name), bytes);
    }

    private static java.util.Map<Path, java.nio.file.attribute.FileTime> modifiedTimes(NodeServer node)
            throws IOException {
        java.util.Map<Path, java.nio.file.attribute.FileTime> times = new java.util.HashMap<>();
        for (Path chunk : chunkFiles(node)) {
            times.put(chunk, Files.getLastModifiedTime(chunk));
        }
        return times;
    }

    private static void corrupt(Path chunk) throws IOException {
        byte[] bytes = Files.readAllBytes(chunk);
        bytes[0] ^= 0x5A;
        Files.write(chunk, bytes);
    }

    private static List<Path> sorted(List<Path> chunkFiles) {
        return chunkFiles.stream().sorted(java.util.Comparator.comparing(p -> p.getFileName().toString())).toList();
    }

    private static List<Path> chunkFiles(NodeServer node) throws IOException {
        Path chunks = node.getDataDir().resolve("chunks");
        try (Stream<Path> files = Files.walk(chunks)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().length() == 64)
                    .toList();
        }
    }
}
