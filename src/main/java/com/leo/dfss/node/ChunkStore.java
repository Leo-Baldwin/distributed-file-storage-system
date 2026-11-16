package com.leo.dfss.node;

import com.leo.dfss.common.ChunkHashes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 * - Deduplication: the same bytes are only ever stored once. A damaged existing copy is
 *   detected and rewritten rather than silently kept.
 * - Durability: a chunk is forced to disk before {@link #put} returns, so an acknowledged chunk
 *   survives a power cut.
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
        if (Files.exists(target) && isIntact(target, hash)) {
            return false; // identical content already stored, and the stored copy is good
        }
        // Either new, or the existing copy is damaged (e.g. truncated by a power cut): (re)write it

        // Write to a temporary file, force it to disk, rename it into place, then force the
        // directory entry to disk. Only then is it safe to acknowledge the chunk as stored:
        // a crash at any point leaves either no file or a complete one under the hash name.
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".incoming-", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(target.getParent());
        } finally {
            Files.deleteIfExists(temp);
        }
        return true;
    }

    /** @return true if the file at {@code path} hashes to {@code hash} */
    private static boolean isIntact(Path path, String hash) {
        try {
            return ChunkHashes.sha256Hex(Files.readAllBytes(path)).equals(hash);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Flushes a directory's entries to disk so a just-renamed file survives a power cut.
     * Supported on Linux (where real nodes run); some platforms refuse to open a directory,
     * which is ignored.
     */
    private static void forceDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
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
