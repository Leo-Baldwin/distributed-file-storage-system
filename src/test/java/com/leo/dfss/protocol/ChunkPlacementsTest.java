package com.leo.dfss.protocol;

import com.leo.dfss.common.ChunkHashes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkPlacementsTest {

    @Test
    void encodeThenDecode_roundTrips() {
        String a = ChunkHashes.sha256Hex(new byte[]{1});
        String b = ChunkHashes.sha256Hex(new byte[]{2});
        List<ChunkPlacements.Placement> in = List.of(
                new ChunkPlacements.Placement(a, new int[]{0, 2}),
                new ChunkPlacements.Placement(b, new int[]{}));

        List<ChunkPlacements.Placement> out = ChunkPlacements.decode(ChunkPlacements.encode(in), 3);

        assertEquals(2, out.size());
        assertEquals(a, out.get(0).hash());
        assertArrayEquals(new int[]{0, 2}, out.get(0).nodeIndexes());
        assertEquals(b, out.get(1).hash());
        assertEquals(0, out.get(1).nodeIndexes().length);
    }

    @Test
    void decode_rejectsTruncatedOrOutOfRangeInput() {
        byte[] encoded = ChunkPlacements.encode(List.of(
                new ChunkPlacements.Placement(ChunkHashes.sha256Hex(new byte[]{1}), new int[]{5})));
        assertThrows(IllegalArgumentException.class, () -> ChunkPlacements.decode(encoded, 3));
        assertThrows(IllegalArgumentException.class,
                () -> ChunkPlacements.decode(java.util.Arrays.copyOf(encoded, 20), 10));
    }
}
