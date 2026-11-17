package com.leo.dfss.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Chunks are addressed by the SHA-256 of their bytes. This class computes and validates those
 * addresses, and packs lists of them into binary message bodies (32 bytes per hash), because a
 * large file's hash list can be far bigger than the 64 KiB JSON header limit.
 */
public final class ChunkHashes {

    public static final int HASH_BYTES = 32;

    private static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final HexFormat HEX = HexFormat.of();

    private ChunkHashes() {
    }

    /** @return the lowercase hex SHA-256 of the given bytes */
    public static String sha256Hex(byte[] data) {
        return HEX.formatHex(digest().digest(data));
    }

    /**
     * @return true if {@code hash} is exactly 64 lowercase hex characters. Because a valid hash
     * can't contain path separators or "..", it is safe to use as a file name.
     */
    public static boolean isValid(String hash) {
        return hash != null && HEX_SHA256.matcher(hash).matches();
    }

    /** @throws IllegalArgumentException if {@code hash} is not a valid chunk hash */
    public static String requireValid(String hash) {
        if (!isValid(hash)) {
            throw new IllegalArgumentException("Not a valid chunk hash: " + hash);
        }
        return hash;
    }

    /** Packs hex hashes into a binary body of 32 bytes each. */
    public static byte[] pack(List<String> hashes) {
        byte[] out = new byte[hashes.size() * HASH_BYTES];
        for (int i = 0; i < hashes.size(); i++) {
            byte[] raw = HEX.parseHex(requireValid(hashes.get(i)));
            System.arraycopy(raw, 0, out, i * HASH_BYTES, HASH_BYTES);
        }
        return out;
    }

    /**
     * Unpacks a binary body produced by {@link #pack}.
     *
     * @throws IllegalArgumentException if the length isn't a multiple of 32
     */
    public static List<String> unpack(byte[] body) {
        if (body == null) {
            return List.of();
        }
        if (body.length % HASH_BYTES != 0) {
            throw new IllegalArgumentException("Hash list length " + body.length + " is not a multiple of " + HASH_BYTES);
        }
        List<String> hashes = new ArrayList<>(body.length / HASH_BYTES);
        for (int off = 0; off < body.length; off += HASH_BYTES) {
            hashes.add(HEX.formatHex(Arrays.copyOfRange(body, off, off + HASH_BYTES)));
        }
        return hashes;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
