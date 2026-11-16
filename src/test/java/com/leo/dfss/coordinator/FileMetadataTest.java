package com.leo.dfss.coordinator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileMetadataTest {

    @Test
    void totalChunks_usesCeilingDivision() {
        // total=10, chunk=4 => 3 chunks (4,4,2)
        assertEquals(3, FileMetadata.totalChunks(10, 4));
    }

    @Test
    void totalChunks_exactDivision() {
        assertEquals(3, FileMetadata.totalChunks(12, 4));
    }

    @Test
    void totalChunks_emptyFileHasNoChunks() {
        assertEquals(0, FileMetadata.totalChunks(0, 4));
    }
}
