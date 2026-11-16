package com.leo.dfss.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkHashesTest {

    @Test
    void sha256_matchesKnownVector() {
        // FIPS 180-2 test vector for "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ChunkHashes.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void packThenUnpack_roundTrips() {
        List<String> hashes = List.of(ChunkHashes.sha256Hex(new byte[]{1}), ChunkHashes.sha256Hex(new byte[]{2}));
        byte[] packed = ChunkHashes.pack(hashes);
        assertEquals(64, packed.length);
        assertEquals(hashes, ChunkHashes.unpack(packed));
        assertEquals(List.of(), ChunkHashes.unpack(null));
    }

    @Test
    void unpack_rejectsPartialHashes() {
        assertThrows(IllegalArgumentException.class, () -> ChunkHashes.unpack(new byte[33]));
    }

    @Test
    void isValid_acceptsOnlyLowercase64Hex() {
        assertTrue(ChunkHashes.isValid("a".repeat(64)));
        assertFalse(ChunkHashes.isValid("A".repeat(64)));
        assertFalse(ChunkHashes.isValid("a".repeat(63)));
        assertFalse(ChunkHashes.isValid("../" + "a".repeat(61)));
        assertFalse(ChunkHashes.isValid(null));
    }
}
