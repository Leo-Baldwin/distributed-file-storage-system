package com.leo.dfss.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Settings for a Coordinator process.
 *
 * @param port               TCP port to listen on (0 picks a free port, useful in tests)
 * @param dataDir            where the Coordinator keeps its metadata database
 * @param replicationFactor  number of copies of each chunk to place, each on a different failure domain
 * @param heartbeatTimeoutMs a node with no heartbeat for this long is marked DOWN
 * @param sweepIntervalMs    how often the heartbeat sweeper runs
 */
public record CoordinatorConfig(int port, Path dataDir, int replicationFactor,
                                long heartbeatTimeoutMs, long sweepIntervalMs) {

    public static final int DEFAULT_PORT = 9000;
    public static final Path DEFAULT_DATA_DIR = Path.of("coordinator-data");
    public static final int DEFAULT_REPLICATION_FACTOR = 3;
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 15_000;
    public static final long DEFAULT_SWEEP_INTERVAL_MS = 5_000;

    public CoordinatorConfig {
        if (port < 0 || port > 65_535) throw new IllegalArgumentException("port out of range: " + port);
        Objects.requireNonNull(dataDir, "dataDir");
        if (replicationFactor < 1) throw new IllegalArgumentException("replicationFactor must be >= 1");
        if (heartbeatTimeoutMs <= 0 || sweepIntervalMs <= 0) {
            throw new IllegalArgumentException("heartbeat timeout and sweep interval must be positive");
        }
    }

    public static CoordinatorConfig defaults() {
        return of(DEFAULT_PORT, DEFAULT_DATA_DIR);
    }

    /** Default settings on the given port and data directory. */
    public static CoordinatorConfig of(int port, Path dataDir) {
        return new CoordinatorConfig(port, dataDir, DEFAULT_REPLICATION_FACTOR,
                DEFAULT_HEARTBEAT_TIMEOUT_MS, DEFAULT_SWEEP_INTERVAL_MS);
    }

    /** @return the SQLite metadata file inside {@link #dataDir} */
    public Path metadataFile() {
        return dataDir.resolve("metadata.db");
    }
}
