package com.leo.dfss.protocol;

import com.leo.dfss.common.ChunkHashes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Binary encoding of "which nodes hold each chunk", used as the body of FILES_GET_RESPONSE.
 *
 * For each chunk, in file order:
 * <pre>
 *   [32 bytes] chunk hash
 *   [1 byte]   n = number of nodes holding it (0-255)
 *   [n x 2 B]  indexes into the response's node list
 * </pre>
 */
public final class ChunkPlacements {

    /** One chunk of a file and the indexes of the nodes that hold it. */
    public record Placement(String hash, int[] nodeIndexes) {
    }

    private ChunkPlacements() {
    }

    public static byte[] encode(List<Placement> placements) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            for (Placement p : placements) {
                out.write(HexFormat.of().parseHex(ChunkHashes.requireValid(p.hash())));
                int n = Math.min(p.nodeIndexes().length, 255);
                out.writeByte(n);
                for (int i = 0; i < n; i++) {
                    out.writeShort(p.nodeIndexes()[i]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // can't happen for an in-memory stream
        }
        return buf.toByteArray();
    }

    /**
     * @param nodeCount size of the node list the indexes refer to
     * @throws IllegalArgumentException if the body is malformed or refers to unknown nodes
     */
    public static List<Placement> decode(byte[] body, int nodeCount) {
        List<Placement> placements = new ArrayList<>();
        if (body == null) {
            return placements;
        }
        ByteBuffer in = ByteBuffer.wrap(body);
        try {
            while (in.hasRemaining()) {
                byte[] raw = new byte[ChunkHashes.HASH_BYTES];
                in.get(raw);
                int n = Byte.toUnsignedInt(in.get());
                int[] indexes = new int[n];
                for (int i = 0; i < n; i++) {
                    indexes[i] = Short.toUnsignedInt(in.getShort());
                    if (indexes[i] >= nodeCount) {
                        throw new IllegalArgumentException("Node index " + indexes[i] + " out of range");
                    }
                }
                placements.add(new Placement(HexFormat.of().formatHex(raw), indexes));
            }
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Truncated chunk placement list", e);
        }
        return placements;
    }
}
