package com.overdrive.app.byd.cloud.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lookup tables for the Bangcle white-box AES cipher.
 * 
 * Tables are extracted from BYD's libencrypt.so and shipped as a binary asset.
 * Binary format: BGTB magic (4B) + version (2B LE) + count (2B LE) + 
 *                index (8 × 8B) + concatenated raw table data.
 * 
 * Port of: pyBYD/src/pybyd/_crypto/_bangcle_block.py (BangcleTables NamedTuple)
 */
public final class BangcleTables {

    private static final byte[] MAGIC = { 'B', 'G', 'T', 'B' };
    private static final int VERSION = 1;
    private static final int TABLE_COUNT = 8;
    private static final int HEADER_SIZE = 4 + 2 + 2; // magic + version + count
    private static final int INDEX_ENTRY_SIZE = 4 + 4; // offset + length

    // Expected sizes for each table
    public static final int INV_ROUND_SIZE  = 0x28000;
    public static final int INV_XOR_SIZE    = 0x3C000;
    public static final int INV_FIRST_SIZE  = 0x1000;
    public static final int ROUND_SIZE      = 0x28000;
    public static final int XOR_SIZE        = 0x3C000;
    public static final int FINAL_SIZE      = 0x1000;
    public static final int PERM_DECRYPT_SIZE = 8;
    public static final int PERM_ENCRYPT_SIZE = 8;

    private static final int[] EXPECTED_SIZES = {
        INV_ROUND_SIZE, INV_XOR_SIZE, INV_FIRST_SIZE,
        ROUND_SIZE, XOR_SIZE, FINAL_SIZE,
        PERM_DECRYPT_SIZE, PERM_ENCRYPT_SIZE
    };

    private static final String[] TABLE_NAMES = {
        "invRound", "invXor", "invFirst",
        "round", "xor", "final",
        "permDecrypt", "permEncrypt"
    };

    public final byte[] invRound;
    public final byte[] invXor;
    public final byte[] invFirst;
    public final byte[] round;
    public final byte[] xor;
    public final byte[] finalTable;
    public final byte[] permDecrypt;
    public final byte[] permEncrypt;

    public BangcleTables(byte[] invRound, byte[] invXor, byte[] invFirst,
                         byte[] round, byte[] xor, byte[] finalTable,
                         byte[] permDecrypt, byte[] permEncrypt) {
        this.invRound = invRound;
        this.invXor = invXor;
        this.invFirst = invFirst;
        this.round = round;
        this.xor = xor;
        this.finalTable = finalTable;
        this.permDecrypt = permDecrypt;
        this.permEncrypt = permEncrypt;
    }

    /**
     * Load tables from a binary input stream (BGTB format).
     */
    public static BangcleTables loadFromStream(InputStream is) throws IOException {
        byte[] data = readAllBytes(is);
        return loadFromBytes(data);
    }

    /**
     * Load tables from raw binary data (BGTB format).
     */
    public static BangcleTables loadFromBytes(byte[] data) throws IOException {
        int minSize = HEADER_SIZE + TABLE_COUNT * INDEX_ENTRY_SIZE;
        if (data.length < minSize) {
            throw new IOException("Table file too short: " + data.length + " bytes");
        }

        // Verify magic
        for (int i = 0; i < 4; i++) {
            if (data[i] != MAGIC[i]) {
                throw new IOException("Bad magic: expected BGTB");
            }
        }

        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        header.position(4);
        int version = header.getShort() & 0xFFFF;
        if (version != VERSION) {
            throw new IOException("Unsupported table version: " + version);
        }

        int count = header.getShort() & 0xFFFF;
        if (count != TABLE_COUNT) {
            throw new IOException("Expected " + TABLE_COUNT + " tables, got " + count);
        }

        // Parse index and extract tables
        byte[][] tables = new byte[TABLE_COUNT][];
        for (int i = 0; i < TABLE_COUNT; i++) {
            int idxOffset = HEADER_SIZE + i * INDEX_ENTRY_SIZE;
            ByteBuffer idx = ByteBuffer.wrap(data, idxOffset, INDEX_ENTRY_SIZE)
                    .order(ByteOrder.LITTLE_ENDIAN);
            int offset = idx.getInt();
            int length = idx.getInt();

            if (length != EXPECTED_SIZES[i]) {
                throw new IOException("Table " + TABLE_NAMES[i] + ": expected " +
                        EXPECTED_SIZES[i] + " bytes, got " + length);
            }
            if (offset + length > data.length) {
                throw new IOException("Table " + TABLE_NAMES[i] + ": data extends beyond file");
            }

            tables[i] = new byte[length];
            System.arraycopy(data, offset, tables[i], 0, length);
        }

        return new BangcleTables(
                tables[0], tables[1], tables[2],
                tables[3], tables[4], tables[5],
                tables[6], tables[7]
        );
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
