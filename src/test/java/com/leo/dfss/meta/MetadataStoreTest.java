package com.leo.dfss.meta;

import com.leo.dfss.common.ChunkHashes;
import com.leo.dfss.coordinator.FileMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataStoreTest {

    @TempDir
    Path tempDir;

    private static FileMetadata uploading(String id) {
        return new FileMetadata(id, "a.bin", 10, 4, FileMetadata.Status.UPLOADING, 1000);
    }

    @Test
    void filesAndNodes_surviveReopening() {
        Path db = tempDir.resolve("meta.db");
        List<String> hashes = List.of(ChunkHashes.sha256Hex(new byte[]{1}), ChunkHashes.sha256Hex(new byte[]{2}),
                ChunkHashes.sha256Hex(new byte[]{1}));

        try (MetadataStore store = MetadataStore.open(db)) {
            store.insertFile(uploading("f1"));
            assertTrue(store.commitFile("f1", hashes, 2000));
            store.upsertNode(new MetadataStore.StoredNode("n1", "10.0.0.5", 9101, "host-a", 100, 50, 3000));
        }

        try (MetadataStore store = MetadataStore.open(db)) {
            FileMetadata f = store.getFile("f1").orElseThrow();
            assertEquals(FileMetadata.Status.COMPLETE, f.status());
            assertEquals(hashes, store.getFileChunks("f1"), "order and repeats must be preserved");
            assertEquals(List.of(new MetadataStore.StoredNode("n1", "10.0.0.5", 9101, "host-a", 100, 50, 3000)),
                    store.loadNodes());
        }
    }

    @Test
    void aFileCanOnlyBeCommittedOnce() {
        try (MetadataStore store = MetadataStore.open(tempDir.resolve("meta.db"))) {
            store.insertFile(uploading("f1"));
            assertTrue(store.commitFile("f1", List.of(), 1));
            assertFalse(store.commitFile("f1", List.of(), 2));
            assertFalse(store.commitFile("missing", List.of(), 3));
        }
    }
}
