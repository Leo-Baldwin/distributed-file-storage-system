package com.leo.dfss.meta;

import com.leo.dfss.coordinator.FileMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Coordinator's durable metadata, in a single SQLite file.
 *
 * Stores what only the Coordinator knows: the files, the ordered chunk hashes that make up each
 * committed file, and every node it has seen. It deliberately does NOT store which node holds
 * which chunk: nodes report that themselves on (re)registration, so it is rebuilt in memory and
 * can never go stale on disk.
 *
 * Thread-safety: one JDBC connection, every method synchronised.
 */
public final class MetadataStore implements AutoCloseable {

    /** A node as last seen, used to restore the registry after a Coordinator restart. */
    public record StoredNode(String nodeId, String host, int port, String failureDomain,
                             long capacityBytes, long freeBytes, long lastSeenEpochMs) {
    }

    private final Connection db;

    private MetadataStore(Connection db) {
        this.db = db;
    }

    /** Opens (creating if needed) the metadata database at {@code dbFile}. */
    public static MetadataStore open(Path dbFile) {
        try {
            Path parent = dbFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");     // crash-safe, readers don't block the writer
                st.execute("PRAGMA synchronous=FULL");      // a committed file survives power loss
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS nodes (
                            node_id        TEXT PRIMARY KEY,
                            host           TEXT NOT NULL,
                            port           INTEGER NOT NULL,
                            failure_domain TEXT NOT NULL,
                            capacity_bytes INTEGER NOT NULL,
                            free_bytes     INTEGER NOT NULL,
                            last_seen_ms   INTEGER NOT NULL
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS files (
                            file_id      TEXT PRIMARY KEY,
                            name         TEXT NOT NULL,
                            size_bytes   INTEGER NOT NULL,
                            chunk_size   INTEGER NOT NULL,
                            status       TEXT NOT NULL,
                            created_ms   INTEGER NOT NULL,
                            committed_ms INTEGER
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS file_chunks (
                            file_id     TEXT NOT NULL REFERENCES files(file_id) ON DELETE CASCADE,
                            chunk_index INTEGER NOT NULL,
                            hash        TEXT NOT NULL,
                            PRIMARY KEY (file_id, chunk_index)
                        )""");
                // Lets later phases (repair, garbage collection) find every file using a chunk
                st.execute("CREATE INDEX IF NOT EXISTS file_chunks_by_hash ON file_chunks(hash)");
            }
            return new MetadataStore(db);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot open metadata database " + dbFile, e);
        }
    }

    // ---------------------------------------------------------------- nodes

    public synchronized void upsertNode(StoredNode n) {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO nodes (node_id, host, port, failure_domain, capacity_bytes, free_bytes, last_seen_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(node_id) DO UPDATE SET host=excluded.host, port=excluded.port,
                    failure_domain=excluded.failure_domain, capacity_bytes=excluded.capacity_bytes,
                    free_bytes=excluded.free_bytes, last_seen_ms=excluded.last_seen_ms""")) {
            ps.setString(1, n.nodeId());
            ps.setString(2, n.host());
            ps.setInt(3, n.port());
            ps.setString(4, n.failureDomain());
            ps.setLong(5, n.capacityBytes());
            ps.setLong(6, n.freeBytes());
            ps.setLong(7, n.lastSeenEpochMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataException("upsertNode", e);
        }
    }

    public synchronized List<StoredNode> loadNodes() {
        List<StoredNode> nodes = new ArrayList<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT node_id, host, port, failure_domain, capacity_bytes, "
                     + "free_bytes, last_seen_ms FROM nodes")) {
            while (rs.next()) {
                nodes.add(new StoredNode(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)));
            }
        } catch (SQLException e) {
            throw new MetadataException("loadNodes", e);
        }
        return nodes;
    }

    // ---------------------------------------------------------------- files

    /** Records a new file in the UPLOADING state. */
    public synchronized void insertFile(FileMetadata f) {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO files (file_id, name, size_bytes, chunk_size, status, created_ms) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, f.fileId());
            ps.setString(2, f.fileName());
            ps.setLong(3, f.totalSizeBytes());
            ps.setInt(4, f.chunkSizeBytes());
            ps.setString(5, f.status().name());
            ps.setLong(6, f.createdEpochMs());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MetadataException("insertFile", e);
        }
    }

    public synchronized Optional<FileMetadata> getFile(String fileId) {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT file_id, name, size_bytes, chunk_size, status, created_ms FROM files WHERE file_id = ?")) {
            ps.setString(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FileMetadata(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4),
                        FileMetadata.Status.valueOf(rs.getString(5)), rs.getLong(6)));
            }
        } catch (SQLException e) {
            throw new MetadataException("getFile", e);
        }
    }

    /**
     * Atomically stores the file's chunk list and marks it COMPLETE.
     *
     * @return false if the file doesn't exist or isn't UPLOADING (so a file can only be committed once)
     */
    public synchronized boolean commitFile(String fileId, List<String> chunkHashes, long committedEpochMs) {
        try {
            db.setAutoCommit(false);
            try {
                try (PreparedStatement ps = db.prepareStatement(
                        "UPDATE files SET status = 'COMPLETE', committed_ms = ? WHERE file_id = ? AND status = 'UPLOADING'")) {
                    ps.setLong(1, committedEpochMs);
                    ps.setString(2, fileId);
                    if (ps.executeUpdate() != 1) {
                        db.rollback();
                        return false;
                    }
                }
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO file_chunks (file_id, chunk_index, hash) VALUES (?, ?, ?)")) {
                    for (int i = 0; i < chunkHashes.size(); i++) {
                        ps.setString(1, fileId);
                        ps.setInt(2, i);
                        ps.setString(3, chunkHashes.get(i));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                db.commit();
                return true;
            } catch (SQLException e) {
                db.rollback();
                throw e;
            } finally {
                db.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new MetadataException("commitFile", e);
        }
    }

    /** @return the committed file's chunk hashes in order (empty if none) */
    public synchronized List<String> getFileChunks(String fileId) {
        List<String> hashes = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT hash FROM file_chunks WHERE file_id = ? ORDER BY chunk_index")) {
            ps.setString(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hashes.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new MetadataException("getFileChunks", e);
        }
        return hashes;
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
        }
    }

    /** Unchecked wrapper so callers aren't forced to handle SQL details. */
    public static final class MetadataException extends RuntimeException {
        MetadataException(String operation, SQLException cause) {
            super("Metadata " + operation + " failed: " + cause.getMessage(), cause);
        }
    }
}
