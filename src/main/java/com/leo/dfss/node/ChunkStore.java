package com.leo.dfss.node;

import com.leo.dfss.common.ChunkHashes;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Content-addressed chunk storage on a Node's disk.
 *
 * Each chunk is stored under the SHA-256 of its bytes:
 * <pre>
 *   &lt;baseDir&gt;/chunks/ab/cd/abcd...(64 hex chars)
 * </pre>
 * The two directory levels keep any one directory from holding millions of files.
 *
 * Properties that follow from addressing by hash:
 * - Integrity: {@link #put} rejects bytes that don't match the hash they're stored under.
 * - Deduplication: the same bytes are only ever stored once.
 * - Safety: a valid hash is 64 hex characters, so it can't be used to escape the chunks directory.
 */
public class ChunkStore {

    private final Path baseDir;
    private final Path chunksDir;

    public ChunkStore(Path baseDir) {
        this.baseDir = baseDir;
        this.chunksDir = baseDir.resolve("chunks");
    }

    /**
     * Stores a chunk under its hash.
     *
     * @return true if the chunk was newly written, false if it was already present
     * @throws IllegalArgumentException if the hash is invalid or doesn't match the data
     */
    public boolean put(String hash, byte[] data) throws IOException {
        ChunkHashes.requireValid(hash);
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }
        String actual = ChunkHashes.sha256Hex(data);
        if (!actual.equals(hash)) {
            throw new IllegalArgumentException("Chunk data does not match its hash (expected " + hash
                    + ", got " + actual + ")");
        }

        Path target = pathFor(hash);
        if (Files.exists(target)) {
            return false; // identical content already stored
        }

        // Write to a temporary file then rename, so a crash never leaves a partial chunk under a valid name
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".incoming-", ".tmp");
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return true;
    }

    /**
     * Reads a chunk.
     *
     * @return the chunk bytes, or null if this node doesn't hold the chunk
     */
    public byte[] get(String hash) throws IOException {
        ChunkHashes.requireValid(hash);
        try {
            return Files.readAllBytes(pathFor(hash));
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** @return true if this node holds the chunk (false for invalid hashes) */
    public boolean has(String hash) {
        return ChunkHashes.isValid(hash) && Files.exists(pathFor(hash));
    }

    /**
     * Lists every chunk hash stored on disk. Used to report this node's inventory to the
     * Coordinator, which rebuilds chunk locations from the nodes themselves.
     */
    public List<String> listHashes() throws IOException {
        List<String> hashes = new ArrayList<>();
        if (!Files.isDirectory(chunksDir)) {
            return hashes;
        }
        try (Stream<Path> files = Files.walk(chunksDir, 3)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(ChunkHashes::isValid) // skips leftover temp files
                    .forEach(hashes::add);
        }
        return hashes;
    }

    private Path pathFor(String hash) {
        return chunksDir.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash);
    }

    /** @return base directory used by this ChunkStore */
    public Path getBaseDir() {
        return baseDir;
    }

    /** @return root directory that holds the chunk files */
    public Path getChunksDir() {
        return chunksDir;
    }
}
