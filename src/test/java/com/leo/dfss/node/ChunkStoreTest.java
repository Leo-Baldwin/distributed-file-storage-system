package com.leo.dfss.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void writeThenReadChunk_roundTripsBytes() throws Exception {
        ChunkStore store = new ChunkStore(tempDir);

        String fileId = UUID.randomUUID().toString();
        int chunkIndex = 0;

        byte[] data = new byte[8192];
        new Random(42).nextBytes(data);

        store.writeChunk(fileId, chunkIndex, data);

        assertTrue(store.chunkExists(fileId, chunkIndex));

        byte[] read = store.readChunk(fileId, chunkIndex);
        assertArrayEquals(data, read);
    }

    @Test
    void chunkExists_invalidInputs_returnFalse() {
        ChunkStore store = new ChunkStore(tempDir);

        assertFalse(store.chunkExists(null, 0));
        assertFalse(store.chunkExists("", 0));
        assertFalse(store.chunkExists(UUID.randomUUID().toString(), -1));
    }

    @Test
    void pathTraversalFileIds_areRejected() {
        ChunkStore store = new ChunkStore(tempDir.resolve("store"));
        byte[] data = {1, 2, 3};

        for (String bad : new String[]{"../escaped", "../../etc", "a/b", "file-123", "/abs/path",
                UUID.randomUUID().toString().toUpperCase()}) {
            assertThrows(IllegalArgumentException.class, () -> store.writeChunk(bad, 0, data), bad);
            assertThrows(IllegalArgumentException.class, () -> store.readChunk(bad, 0), bad);
            assertFalse(store.chunkExists(bad, 0), bad);
        }
        assertFalse(Files.exists(tempDir.resolve("escaped")));
    }
}
