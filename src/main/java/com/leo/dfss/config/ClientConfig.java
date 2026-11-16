package com.leo.dfss.config;

import com.leo.dfss.transport.TcpMessageReader;

import java.util.Objects;

/**
 * Settings for upload/download clients.
 *
 * @param coordinatorHost Coordinator address
 * @param coordinatorPort Coordinator port
 * @param chunkSizeBytes  size files are split into on upload
 */
public record ClientConfig(String coordinatorHost, int coordinatorPort, int chunkSizeBytes) {

    public static final int DEFAULT_CHUNK_SIZE_BYTES = 4 * 1024 * 1024; // 4 MiB

    public ClientConfig {
        Objects.requireNonNull(coordinatorHost, "coordinatorHost");
        if (coordinatorPort <= 0 || coordinatorPort > 65_535) {
            throw new IllegalArgumentException("coordinatorPort out of range: " + coordinatorPort);
        }
        if (chunkSizeBytes <= 0 || chunkSizeBytes > TcpMessageReader.MAX_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "chunkSizeBytes must be between 1 and " + TcpMessageReader.MAX_BODY_BYTES);
        }
    }

    public static ClientConfig defaults() {
        return local(CoordinatorConfig.DEFAULT_PORT);
    }

    public static ClientConfig local(int coordinatorPort) {
        return new ClientConfig("localhost", coordinatorPort, DEFAULT_CHUNK_SIZE_BYTES);
    }

    public ClientConfig withChunkSize(int bytes) {
        return new ClientConfig(coordinatorHost, coordinatorPort, bytes);
    }
}
