package com.leo.dfss.integration;

import com.leo.dfss.config.CoordinatorConfig;
import com.leo.dfss.config.NodeConfig;
import com.leo.dfss.coordinator.CoordinatorServer;
import com.leo.dfss.coordinator.NodeInfo;
import com.leo.dfss.node.NodeServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NodeLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void nodeId_survivesRestart() throws Exception {
        try (TestCluster cluster = TestCluster.start(tempDir, 0)) {
            Path data = tempDir.resolve("node-data");
            NodeConfig config = NodeConfig.local(0, data, cluster.coordinator.getPort());

            NodeServer first = new NodeServer(config);
            String id = first.getNodeId();
            first.shutdown();

            NodeServer second = new NodeServer(config);
            assertEquals(id, second.getNodeId());
        }
    }

    @Test
    void node_registersWithItsBoundPortAndDomain() throws Exception {
        try (TestCluster cluster = TestCluster.start(tempDir, 1)) {
            NodeServer node = cluster.nodes.get(0);
            NodeInfo info = cluster.coordinator.getNodes().get(node.getNodeId());

            assertNotNull(info);
            assertEquals(node.getPort(), info.getPort());
            assertEquals("127.0.0.1", info.getHost()); // no advertise host, so the connecting address is used
            assertEquals("test-domain-1", info.getFailureDomain());
            assertTrue(info.getCapacityBytes() > 0);
        }
    }

    @Test
    void node_reRegistersAfterCoordinatorRestart() throws Exception {
        CoordinatorServer first = new CoordinatorServer(CoordinatorConfig.onPort(0));
        startDaemon(first::start);
        first.awaitReady(5, TimeUnit.SECONDS);
        int port = first.getPort();

        NodeConfig config = new NodeConfig(0, tempDir.resolve("n"), null, "localhost", port, "d",
                100); // fast heartbeats so the restart is noticed quickly
        NodeServer node = new NodeServer(config);
        startDaemon(node::start);
        awaitTrue(() -> first.countActiveNodes() == 1);

        // Restart the Coordinator on the same port: it has forgotten every node
        first.shutdown();
        CoordinatorServer second = new CoordinatorServer(CoordinatorConfig.onPort(port));
        startDaemon(second::start);
        second.awaitReady(5, TimeUnit.SECONDS);

        try {
            awaitTrue(() -> second.getNodes().containsKey(node.getNodeId()));
        } finally {
            node.shutdown();
            second.shutdown();
        }
    }

    private static void startDaemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("Condition not met within 10s");
            Thread.sleep(20);
        }
    }
}
