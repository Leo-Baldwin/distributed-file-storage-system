package com.leo.dfss.node;

import com.leo.dfss.common.ChunkHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ChunkStoreTest {

    @TempDir
    Path tempDir;

    private static byte[] randomBytes(int n, long seed) {
        byte[] data = new byte[n];
        new Random(seed).nextBytes(data);
        return data;
    }

    @Test
    void putThenGet_roundTripsBytes() throws Exception {
        ChunkStore store = new ChunkStore(tempDir);
        byte[] data = randomBytes(8192, 42);
        String hash = ChunkHashes.sha256Hex(data);

        assertTrue(store.put(hash, data));
        assertTrue(store.has(hash));
        assertArrayEquals(data, store.get(hash));
        assertTrue(Files.exists(tempDir.resolve("chunks").resolve(hash.substring(0, 2))
                .resolve(hash.substring(2, 4)).resolve(hash)));
    }

    @Test
    void identicalContent_isStoredOnce() throws Exception {
        ChunkStore store = new ChunkStore(tempDir);
        byte[] data = randomBytes(1000, 1);
        String hash = ChunkHashes.sha256Hex(data);

        assertTrue(store.put(hash, data));
        assertFalse(store.put(hash, data.clone()), "second put of the same content should be a no-op");
        assertEquals(1, store.listHashes().size());
    }

    @Test
    void damagedExistingCopy_isDetectedAndRewritten() throws Exception {
        ChunkStore store = new ChunkStore(tempDir);
        byte[] data = randomBytes(5000, 5);
        String hash = ChunkHashes.sha256Hex(data);
        store.put(hash, data);

        // Simulate a power cut that left a truncated file under the valid name
        Path file = tempDir.resolve("chunks").resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash);
        Files.write(file, java.util.Arrays.copyOf(data, 100));

        assertTrue(store.put(hash, data), "a damaged copy must be rewritten, not treated as already stored");
        assertArrayEquals(data, store.get(hash));
    }

    @Test
    void dataNotMatchingItsHash_isRejected() {
        ChunkStore store = new ChunkStore(tempDir);
        byte[] data = randomBytes(100, 2);
        String wrongHash = ChunkHashes.sha256Hex(randomBytes(100, 3));

        assertThrows(IllegalArgumentException.class, () -> store.put(wrongHash, data));
        assertFalse(store.has(wrongHash));
    }

    @Test
    void invalidHashes_areRejected_andCannotEscapeTheStore() throws Exception {
        ChunkStore store = new ChunkStore(tempDir.resolve("store"));
        byte[] data = {1, 2, 3};
        for (String bad : new String[]{"../escaped", "../../etc/passwd", "a/b", "ABC", "", null,
                ChunkHashes.sha256Hex(data).toUpperCase()}) {
            assertThrows(IllegalArgumentException.class, () -> store.put(bad, data), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> store.get(bad), String.valueOf(bad));
            assertFalse(store.has(bad), String.valueOf(bad));
        }
        assertFalse(Files.exists(tempDir.resolve("escaped")));
    }

    @Test
    void missingChunk_returnsNull() throws Exception {
        assertNull(new ChunkStore(tempDir).get(ChunkHashes.sha256Hex(new byte[]{9})));
    }

    @Test
    void listHashes_ignoresLeftoverTempFiles() throws Exception {
        ChunkStore store = new ChunkStore(tempDir);
        byte[] data = randomBytes(64, 4);
        String hash = ChunkHashes.sha256Hex(data);
        store.put(hash, data);
        Files.writeString(tempDir.resolve("chunks").resolve(hash.substring(0, 2)).resolve(".incoming-x.tmp"), "junk");

        assertEquals(java.util.List.of(hash), store.listHashes());
    }
}
