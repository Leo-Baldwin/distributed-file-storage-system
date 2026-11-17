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
import java.util.function.BooleanSupplier;

/**
 * Starts a Coordinator and N storage nodes in-process on free ports, for integration tests.
 * Each node gets its own data directory and failure domain, and heartbeats quickly so restarts
 * are noticed fast.
 */
final class TestCluster implements AutoCloseable {

    static final long FAST_HEARTBEAT_MS = 200;

    private final Path baseDir;
    CoordinatorServer coordinator;
    final List<NodeServer> nodes = new ArrayList<>();
    private int coordinatorPort;

    private TestCluster(Path baseDir) {
        this.baseDir = baseDir;
    }

    static TestCluster start(Path baseDir, int nodeCount) throws Exception {
        TestCluster cluster = new TestCluster(baseDir);
        cluster.coordinator = startCoordinator(0, baseDir.resolve("coordinator"));
        cluster.coordinatorPort = cluster.coordinator.getPort();
        for (int i = 1; i <= nodeCount; i++) {
            cluster.addNode("test-domain-" + i);
        }
        cluster.awaitActiveNodes(nodeCount);
        return cluster;
    }

    /** Starts one more node in its own failure domain. */
    NodeServer addNode(String failureDomain) throws Exception {
        Path dataDir = baseDir.resolve("node-" + (nodes.size() + 1));
        NodeConfig config = new NodeConfig(0, dataDir, null, "localhost", coordinatorPort, failureDomain,
                FAST_HEARTBEAT_MS);
        NodeServer node = new NodeServer(config);
        startDaemon("IT-Node-" + (nodes.size() + 1), node::start);
        node.awaitReady(5, TimeUnit.SECONDS);
        nodes.add(node);
        return node;
    }

    /**
     * Stops the Coordinator and starts a fresh one on the same port and data directory, as after
     * a crash or reboot. Its in-memory state is gone; only what it persisted remains.
     */
    void restartCoordinator() throws Exception {
        coordinator.shutdown();
        coordinator = startCoordinator(coordinatorPort, baseDir.resolve("coordinator"));
    }

    private static CoordinatorServer startCoordinator(int port, Path dataDir) throws Exception {
        CoordinatorServer coordinator = new CoordinatorServer(CoordinatorConfig.of(port, dataDir));
        startDaemon("IT-Coordinator", coordinator::start);
        coordinator.awaitReady(5, TimeUnit.SECONDS);
        return coordinator;
    }

    /** Client config pointing at this cluster's Coordinator, with small chunks for fast tests. */
    ClientConfig client() {
        return ClientConfig.local(coordinatorPort);
    }

    void awaitActiveNodes(int expected) throws InterruptedException {
        awaitTrue(() -> coordinator.countActiveNodes() >= expected,
                () -> "Only " + coordinator.countActiveNodes() + " of " + expected + " nodes registered in time");
    }

    static void awaitTrue(BooleanSupplier condition, java.util.function.Supplier<String> message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(message.get());
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
