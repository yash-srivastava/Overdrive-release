package com.overdrive.app.byd.cloud.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * White-box AES block cipher for Bangcle envelopes.
 * 
 * Uses pre-computed lookup tables extracted from libencrypt.so rather than
 * a standard AES key schedule. The key is embedded in the tables themselves.
 * 
 * Direct port of: pyBYD/src/pybyd/_crypto/_bangcle_block.py
 * Also matches: Niek/BYD-re/bangcle.js
 */
public final class BangcleBlockCipher {

    private BangcleBlockCipher() {}

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Transpose 4×4 block into working state layout (col*8+row). */
    private static void prepareAesMatrix(byte[] input, int inputOff, byte[] state) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                state[col * 8 + row] = input[inputOff + col + row * 4];
            }
        }
    }

    /** Write state back to 4×4 block layout. */
    private static void writeBlockFromMatrix(byte[] state, byte[] output, int outputOff) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                output[outputOff + col + row * 4] = state[col * 8 + row];
            }
        }
    }

    /** Read a little-endian uint32 from a byte array. */
    private static int readU32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
             | ((data[offset + 1] & 0xFF) << 8)
             | ((data[offset + 2] & 0xFF) << 16)
             | ((data[offset + 3] & 0xFF) << 24);
    }

    /** Write a little-endian uint32 to a byte array. */
    private static void writeU32LE(byte[] data, int offset, int value) {
        data[offset]     = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    // ── Block Decrypt ───────────────────────────────────────────────────

    /**
     * Decrypt a single 16-byte block using white-box AES tables.
     * Port of: decrypt_block_auth() in _bangcle_block.py
     */
    public static byte[] decryptBlock(BangcleTables tables, byte[] block, int blockOff) {
        byte[] state = new byte[32];
        byte[] temp64 = new byte[64];
        byte[] tmp32 = new byte[32];
        byte[] output = new byte[16];

        prepareAesMatrix(block, blockOff, state);

        for (int rnd = 9; rnd >= 1; rnd--) {
            int lVar21 = rnd * 4;
            int permPtr = 0;

            for (int i = 0; i < 4; i++) {
                int bVar3 = tables.permDecrypt[permPtr] & 0xFF;
                int lVar16 = i * 8;
                int base = i * 16;

                for (int j = 0; j < 4; j++) {
                    int uVar7 = (bVar3 + j) & 3;
                    int byteVal = state[lVar16 + uVar7] & 0xFF;
                    int idx = byteVal + (i + (lVar21 + uVar7) * 4) * 256;
                    int value = readU32LE(tables.invRound, idx * 4);
                    writeU32LE(temp64, base + j * 4, value);
                }
                permPtr += 2;
            }

            int iVar15 = 1;
            for (int lVar21Xor = 0; lVar21Xor < 4; lVar21Xor++) {
                int pbOffset = lVar21Xor;

                for (int lVar9Xor = 0; lVar9Xor < 4; lVar9Xor++) {
                    int local10 = temp64[pbOffset] & 0xFF;
                    int uVar6 = local10 & 0x0F;
                    int uVar26 = local10 & 0xF0;

                    int localF0 = temp64[pbOffset + 0x10] & 0xFF;
                    int localF1 = temp64[pbOffset + 0x20] & 0xFF;
                    int localF2 = temp64[pbOffset + 0x30] & 0xFF;

                    int lVar2 = lVar9Xor * 0x18 + rnd * 0x60;
                    int iVar25 = iVar15;

                    int[] locals = { localF0, localF1, localF2 };
                    for (int k = 0; k < 3; k++) {
                        int bInner = locals[k];
                        int uVar1 = (bInner << 4) & 0xFF;
                        int uVar27 = uVar6 | uVar1;
                        uVar26 = ((uVar26 >> 4) | ((bInner >> 4) << 4)) & 0xFF;

                        int idx1 = (lVar2 + (iVar25 - 1)) * 0x100 + uVar27;
                        uVar6 = tables.invXor[idx1] & 0x0F;

                        int idx2 = (lVar2 + iVar25) * 0x100 + uVar26;
                        int bNew = tables.invXor[idx2] & 0xFF;
                        uVar26 = (bNew & 0x0F) << 4;
                        iVar25 += 2;
                    }

                    state[lVar9Xor + lVar21Xor * 8] = (byte) ((uVar26 | uVar6) & 0xFF);
                    pbOffset += 4;
                }
                iVar15 += 6;
            }
        }

        // Final round (invFirst table)
        System.arraycopy(state, 0, tmp32, 0, 32);
        int uVar8 = 1, uVar10 = 3, uVar12 = 2;

        for (int row = 0; row < 4; row++) {
            int idx0 = (tmp32[row] & 0xFF) + row * 0x400;
            state[row] = tables.invFirst[idx0];

            int row1 = uVar10 & 3;
            int idx1 = (tmp32[8 + row1] & 0xFF) + row1 * 0x400 + 0x100;
            state[8 + row] = tables.invFirst[idx1];

            int row2 = uVar12 & 3;
            int idx2 = (tmp32[0x10 + row2] & 0xFF) + row2 * 0x400 + 0x200;
            state[0x10 + row] = tables.invFirst[idx2];

            int row3 = uVar8 & 3;
            int idx3 = (tmp32[0x18 + row3] & 0xFF) + row3 * 0x400 + 0x300;
            state[0x18 + row] = tables.invFirst[idx3];

            uVar8++;
            uVar10++;
            uVar12++;
        }

        writeBlockFromMatrix(state, output, 0);
        return output;
    }

    // ── Block Encrypt ───────────────────────────────────────────────────

    /**
     * Encrypt a single 16-byte block using white-box AES tables.
     * Port of: encrypt_block_auth() in _bangcle_block.py
     */
    public static byte[] encryptBlock(BangcleTables tables, byte[] block, int blockOff) {
        byte[] state = new byte[32];
        byte[] temp64 = new byte[64];
        byte[] tmp32 = new byte[32];
        byte[] output = new byte[16];

        prepareAesMatrix(block, blockOff, state);

        for (int rnd = 0; rnd < 9; rnd++) {
            int lVar21 = rnd * 4;
            int permPtr = 0;

            for (int i = 0; i < 4; i++) {
                int bVar4 = tables.permEncrypt[permPtr] & 0xFF;
                int lVar16 = i * 8;
                int base = i * 16;

                for (int j = 0; j < 4; j++) {
                    int uVar8e = (bVar4 + j) & 3;
                    int byteVal = state[lVar16 + uVar8e] & 0xFF;
                    int idx = byteVal + (i + (lVar21 + uVar8e) * 4) * 256;
                    int value = readU32LE(tables.round, idx * 4);
                    writeU32LE(temp64, base + j * 4, value);
                }
                permPtr += 2;
            }

            int iVar16 = 1;
            for (int lVar22 = 0; lVar22 < 4; lVar22++) {
                int pbOffset = lVar22;

                for (int lVar10 = 0; lVar10 < 4; lVar10++) {
                    int local10 = temp64[pbOffset] & 0xFF;
                    int uVar7 = local10 & 0x0F;
                    int uVar26 = local10 & 0xF0;

                    int localF0 = temp64[pbOffset + 0x10] & 0xFF;
                    int localF1 = temp64[pbOffset + 0x20] & 0xFF;
                    int localF2 = temp64[pbOffset + 0x30] & 0xFF;

                    int lVar2 = lVar10 * 0x18 + rnd * 0x60;
                    int iVar25 = iVar16;

                    int[] locals = { localF0, localF1, localF2 };
                    for (int k = 0; k < 3; k++) {
                        int bInner = locals[k];
                        int uVar1 = (bInner << 4) & 0xFF;
                        int uVar27 = uVar7 | uVar1;
                        uVar26 = ((uVar26 >> 4) | ((bInner >> 4) << 4)) & 0xFF;

                        int idx1 = (lVar2 + (iVar25 - 1)) * 0x100 + uVar27;
                        uVar7 = tables.xor[idx1] & 0x0F;

                        int idx2 = (lVar2 + iVar25) * 0x100 + uVar26;
                        int bNew = tables.xor[idx2] & 0xFF;
                        uVar26 = (bNew & 0x0F) << 4;
                        iVar25 += 2;
                    }

                    state[lVar10 + lVar22 * 8] = (byte) ((uVar26 | uVar7) & 0xFF);
                    pbOffset += 4;
                }
                iVar16 += 6;
            }
        }

        // Final round (final table)
        System.arraycopy(state, 0, tmp32, 0, 32);
        int uVar13 = 3, uVar9 = 2, uVar11 = 1, uVar8enc = 0;

        for (int row = 0; row < 4; row++) {
            int row0 = (uVar8enc + row) & 3;
            state[row] = tables.finalTable[(tmp32[row0] & 0xFF) + row0 * 0x400];

            int row1 = (uVar11 + row) & 3;
            state[8 + row] = tables.finalTable[(tmp32[8 + row1] & 0xFF) + row1 * 0x400 + 0x100];

            int row2 = (uVar9 + row) & 3;
            state[0x10 + row] = tables.finalTable[(tmp32[0x10 + row2] & 0xFF) + row2 * 0x400 + 0x200];

            int row3 = (uVar13 + row) & 3;
            state[0x18 + row] = tables.finalTable[(tmp32[0x18 + row3] & 0xFF) + row3 * 0x400 + 0x300];
        }

        writeBlockFromMatrix(state, output, 0);
        return output;
    }

    // ── CBC Mode ────────────────────────────────────────────────────────

    /**
     * Decrypt data using white-box AES in CBC mode.
     */
    public static byte[] decryptCbc(BangcleTables tables, byte[] data, byte[] iv) {
        if (data.length % 16 != 0) {
            throw new IllegalArgumentException("Ciphertext length " + data.length + " not multiple of 16");
        }
        if (iv.length != 16) {
            throw new IllegalArgumentException("IV must be 16 bytes");
        }

        byte[] result = new byte[data.length];
        byte[] prev = new byte[16];
        System.arraycopy(iv, 0, prev, 0, 16);

        for (int offset = 0; offset < data.length; offset += 16) {
            byte[] decrypted = decryptBlock(tables, data, offset);

            // XOR with previous ciphertext block
            for (int i = 0; i < 16; i++) {
                result[offset + i] = (byte) (decrypted[i] ^ prev[i]);
            }

            // Save current ciphertext block as prev
            System.arraycopy(data, offset, prev, 0, 16);
        }

        return result;
    }

    /**
     * Encrypt data using white-box AES in CBC mode.
     */
    public static byte[] encryptCbc(BangcleTables tables, byte[] data, byte[] iv) {
        if (data.length % 16 != 0) {
            throw new IllegalArgumentException("Plaintext length " + data.length + " not multiple of 16");
        }
        if (iv.length != 16) {
            throw new IllegalArgumentException("IV must be 16 bytes");
        }

        byte[] result = new byte[data.length];
        byte[] prev = new byte[16];
        System.arraycopy(iv, 0, prev, 0, 16);
        byte[] xored = new byte[16];

        for (int offset = 0; offset < data.length; offset += 16) {
            // XOR plaintext with previous ciphertext
            for (int i = 0; i < 16; i++) {
                xored[i] = (byte) (data[offset + i] ^ prev[i]);
            }

            byte[] encrypted = encryptBlock(tables, xored, 0);
            System.arraycopy(encrypted, 0, result, offset, 16);
            System.arraycopy(encrypted, 0, prev, 0, 16);
        }

        return result;
    }

    // ── PKCS#7 Padding ──────────────────────────────────────────────────

    /** Add PKCS#7 padding to make data a multiple of 16 bytes. */
    public static byte[] addPkcs7(byte[] data) {
        int remainder = data.length % 16;
        int pad = (remainder == 0) ? 16 : 16 - remainder;
        byte[] padded = new byte[data.length + pad];
        System.arraycopy(data, 0, padded, 0, data.length);
        for (int i = data.length; i < padded.length; i++) {
            padded[i] = (byte) pad;
        }
        return padded;
    }

    /** Strip PKCS#7 padding. */
    public static byte[] stripPkcs7(byte[] data) {
        if (data.length == 0) return data;
        int pad = data[data.length - 1] & 0xFF;
        if (pad == 0 || pad > 16) return data;
        for (int i = data.length - pad; i < data.length; i++) {
            if ((data[i] & 0xFF) != pad) return data;
        }
        byte[] result = new byte[data.length - pad];
        System.arraycopy(data, 0, result, 0, result.length);
        return result;
    }
}
