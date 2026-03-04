package com.leo.dfss.coordinator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileMetadataTest {

    @Test
    void totalChunks_usesCeilingDivision() {
        // total=10, chunk=4 => 3 chunks (4,4,2)
        FileMetadata meta = new FileMetadata("file-1", "a.bin", 10, 4);
        assertEquals(3, meta.getTotalChunks());
    }

    @Test
    void totalChunks_exactDivision() {
        FileMetadata meta = new FileMetadata("file-2", "b.bin", 12, 4);
        assertEquals(3, meta.getTotalChunks());
    }
}