package com.leo.dfss.config;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Settings for a storage Node process.
 *
 * @param port                TCP port to listen on for chunk traffic (0 picks a free port)
 * @param dataDir             where chunks and the node's persistent ID are stored
 * @param advertiseHost       address clients should use to reach this node; null lets the
 *                            Coordinator use the address the node connected from
 * @param coordinatorHost     Coordinator address
 * @param coordinatorPort     Coordinator port
 * @param failureDomain       label for what fails together (usually one per machine); copies of
 *                            a file should land in different domains
 * @param heartbeatIntervalMs how often to send heartbeats
 */
public record NodeConfig(int port,
                         Path dataDir,
                         String advertiseHost,
                         String coordinatorHost,
                         int coordinatorPort,
                         String failureDomain,
                         long heartbeatIntervalMs) {

    public static final int DEFAULT_PORT = 9100;
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000;

    public NodeConfig {
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port out of range: " + port);
        if (coordinatorPort <= 0 || coordinatorPort > 65_535) {
            throw new IllegalArgumentException("coordinatorPort out of range: " + coordinatorPort);
        }
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(coordinatorHost, "coordinatorHost");
        if (heartbeatIntervalMs <= 0) throw new IllegalArgumentException("heartbeatIntervalMs must be positive");
        if (advertiseHost != null && advertiseHost.isBlank()) advertiseHost = null;
        if (failureDomain == null || failureDomain.isBlank()) failureDomain = localHostName();
    }

    /** A node on the given port and data directory, talking to a Coordinator on localhost. */
    public static NodeConfig local(int port, Path dataDir, int coordinatorPort) {
        return new NodeConfig(port, dataDir, null, "localhost", coordinatorPort, null,
                DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    /** Returns a copy with a different failure domain. */
    public NodeConfig withFailureDomain(String domain) {
        return new NodeConfig(port, dataDir, advertiseHost, coordinatorHost, coordinatorPort, domain,
                heartbeatIntervalMs);
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
