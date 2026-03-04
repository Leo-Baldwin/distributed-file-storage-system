package com.leo.dfss.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void writeThenReadChunk_roundTripsBytes() throws Exception {
        ChunkStore store = new ChunkStore(tempDir);

        String fileId = "file-123";
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
        assertFalse(store.chunkExists("file", -1));
    }
}