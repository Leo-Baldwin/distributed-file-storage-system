package com.leo.dfss.config;

/**
 * Settings for a Coordinator process.
 *
 * @param port               TCP port to listen on (0 picks a free port, useful in tests)
 * @param replicationFactor  number of nodes each file is written to
 * @param heartbeatTimeoutMs a node with no heartbeat for this long is marked DOWN
 * @param sweepIntervalMs    how often the heartbeat sweeper runs
 */
public record CoordinatorConfig(int port, int replicationFactor, long heartbeatTimeoutMs, long sweepIntervalMs) {

    public static final int DEFAULT_PORT = 9000;
    public static final int DEFAULT_REPLICATION_FACTOR = 3;
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 15_000;
    public static final long DEFAULT_SWEEP_INTERVAL_MS = 5_000;

    public CoordinatorConfig {
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port out of range: " + port);
        if (replicationFactor < 1) throw new IllegalArgumentException("replicationFactor must be >= 1");
        if (heartbeatTimeoutMs <= 0 || sweepIntervalMs <= 0) {
            throw new IllegalArgumentException("heartbeat timeout and sweep interval must be positive");
        }
    }

    public static CoordinatorConfig defaults() {
        return onPort(DEFAULT_PORT);
    }

    public static CoordinatorConfig onPort(int port) {
        return new CoordinatorConfig(port, DEFAULT_REPLICATION_FACTOR,
                DEFAULT_HEARTBEAT_TIMEOUT_MS, DEFAULT_SWEEP_INTERVAL_MS);
    }
}
