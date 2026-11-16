package com.leo.dfss.integration;

import com.leo.dfss.config.ClientConfig;
import com.leo.dfss.config.CoordinatorConfig;
import com.leo.dfss.config.NodeConfig;
import com.leo.dfss.coordinator.CoordinatorServer;
import com.leo.dfss.node.NodeServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts a Coordinator and N storage nodes in-process on free ports, for integration tests.
 * Each node gets its own data directory and failure domain.
 */
final class TestCluster implements AutoCloseable {

    final CoordinatorServer coordinator;
    final List<NodeServer> nodes = new ArrayList<>();

    private TestCluster(CoordinatorServer coordinator) {
        this.coordinator = coordinator;
    }

    static TestCluster start(Path baseDir, int nodeCount) throws Exception {
        CoordinatorServer coordinator = new CoordinatorServer(CoordinatorConfig.onPort(0));
        startDaemon("IT-Coordinator", coordinator::start);
        coordinator.awaitReady(5, TimeUnit.SECONDS);

        TestCluster cluster = new TestCluster(coordinator);
        for (int i = 1; i <= nodeCount; i++) {
            NodeConfig config = NodeConfig.local(0, baseDir.resolve("node-" + i), coordinator.getPort())
                    .withFailureDomain("test-domain-" + i);
            NodeServer node = new NodeServer(config);
            startDaemon("IT-Node-" + i, node::start);
            node.awaitReady(5, TimeUnit.SECONDS);
            cluster.nodes.add(node);
        }

        cluster.awaitActiveNodes(nodeCount);
        return cluster;
    }

    /** Client config pointing at this cluster's Coordinator. */
    ClientConfig client() {
        return ClientConfig.local(coordinator.getPort());
    }

    void awaitActiveNodes(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (coordinator.countActiveNodes() < expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Only " + coordinator.countActiveNodes() + " of "
                        + expected + " nodes registered in time");
            }
            Thread.sleep(20);
        }
    }

    private static void startDaemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() {
        for (NodeServer node : nodes) {
            try { node.shutdown(); } catch (Exception ignored) {}
        }
        try { coordinator.shutdown(); } catch (Exception ignored) {}
    }
}
