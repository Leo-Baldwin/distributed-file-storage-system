package com.leo.dfss.coordinator;

import com.leo.dfss.common.ChunkHashes;
import com.leo.dfss.config.CoordinatorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A file must never become COMPLETE unless every chunk is actually held by a node. */
class CommitTest {

    @TempDir
    Path tempDir;

    private CoordinatorServer c;
    private String session;
    private final List<String> hashes = List.of(
            ChunkHashes.sha256Hex(new byte[]{1}), ChunkHashes.sha256Hex(new byte[]{2}), ChunkHashes.sha256Hex(new byte[]{3}));

    @BeforeEach
    void setUp() {
        c = new CoordinatorServer(CoordinatorConfig.of(0, tempDir));
        session = c.registerNode("n1", "127.0.0.1", 9101, 1000, "d1");
    }

    @AfterEach
    void tearDown() {
        c.shutdown();
    }

    private String startUpload() {
        return c.initFileUpload("f.bin", 10, 4).file().fileId(); // 3 chunks
    }

    @Test
    void chunksNoNodeHolds_areRefused_untilANodeReportsThem() {
        String fileId = startUpload();

        CoordinatorServer.CommitOutcome missing = c.commitFile(fileId, hashes);
        assertEquals("MISSING_CHUNKS", missing.status());
        assertEquals(3, missing.missingChunks());
        assertEquals(FileMetadata.Status.UPLOADING, c.getFile(fileId).orElseThrow().status());

        assertTrue(c.chunksAdded("n1", session, hashes.subList(0, 2)));
        assertEquals(1, c.commitFile(fileId, hashes).missingChunks());

        assertTrue(c.chunksAdded("n1", session, hashes.subList(2, 3)));
        assertTrue(c.commitFile(fileId, hashes).ok());
        assertEquals(FileMetadata.Status.COMPLETE, c.getFile(fileId).orElseThrow().status());
        assertEquals(hashes, c.getFileChunks(fileId));
    }

    @Test
    void wrongNumberOfChunks_isAnError() {
        String fileId = startUpload();
        c.chunksAdded("n1", session, hashes);
        assertEquals("ERROR", c.commitFile(fileId, hashes.subList(0, 2)).status());
    }

    @Test
    void aFileIsCommittedOnlyOnce() {
        String fileId = startUpload();
        c.chunksAdded("n1", session, hashes);
        assertTrue(c.commitFile(fileId, hashes).ok());
        assertEquals("ERROR", c.commitFile(fileId, hashes).status());
    }

    @Test
    void reportsFromAStaleSession_areRejected() {
        String oldSession = session;
        String newSession = c.registerNode("n1", "127.0.0.1", 9101, 1000, "d1");
        assertNotEquals(oldSession, newSession);
        assertFalse(c.chunksAdded("n1", oldSession, hashes));
        assertFalse(c.handleHeartbeat("n1", oldSession, System.currentTimeMillis(), 0));
        assertTrue(c.handleHeartbeat("n1", newSession, System.currentTimeMillis(), 0));
    }

    @Test
    void absurdFileSizes_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> c.initFileUpload("huge.bin", Long.MAX_VALUE, 4));
    }
}
