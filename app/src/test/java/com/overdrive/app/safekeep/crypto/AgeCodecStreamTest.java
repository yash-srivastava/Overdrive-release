package com.overdrive.app.safekeep.crypto;

import com.google.crypto.tink.subtle.Hkdf;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AgeCodecStreamTest {
    private static final int CHUNK = AgeCodec.CHUNK;
    private static final int SEALED = CHUNK + 16;
    private static final byte[] HEADER = "test header\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void matchesAllocatingReferenceAtChunkAndResumeBoundaries() throws Exception {
        Random random = new Random(42);
        byte[] fileKey = new byte[16];
        byte[] fileNonce = new byte[16];
        random.nextBytes(fileKey);
        random.nextBytes(fileNonce);
        AgeCodec.Material material = new AgeCodec.Material(fileKey, HEADER, fileNonce);
        int prefix = HEADER.length + fileNonce.length;
        File file = File.createTempFile("age-stream", ".bin");
        try {
            for (int length : new int[] {0, 1, CHUNK - 1, CHUNK, CHUNK + 1,
                    2 * CHUNK, 3 * CHUNK + 37}) {
                byte[] plain = new byte[length];
                random.nextBytes(plain);
                Files.write(file.toPath(), plain);
                byte[] expected = reference(plain, fileKey, fileNonce);
                assertEquals(expected.length, material.ciphertextLength(length));
                long[] offsets = {-1, 0, prefix - 1, prefix, prefix + 1,
                        prefix + CHUNK - 1, prefix + CHUNK, prefix + SEALED - 1,
                        prefix + SEALED, prefix + SEALED + 1, prefix + 2L * SEALED,
                        expected.length - 16, expected.length - 1, expected.length,
                        expected.length + 1L};
                for (long offset : offsets) {
                    int start = (int) Math.max(0, Math.min(offset, expected.length));
                    byte[] suffix = Arrays.copyOfRange(expected, start, expected.length);
                    try (InputStream in = AgeCodec.openCiphertext(material, file, length, offset)) {
                        assertArrayEquals("length=" + length + " offset=" + offset, suffix, readMixed(in));
                    }
                }
            }
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    // Deliberately retain the old allocating, per-chunk encryption API as an independent oracle.
    private static byte[] reference(byte[] plain, byte[] fileKey, byte[] fileNonce) throws Exception {
        byte[] key = Hkdf.computeHkdf("HMACSHA256", fileKey, fileNonce,
                "payload".getBytes(StandardCharsets.US_ASCII), 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER);
        out.write(fileNonce);
        for (int offset = 0, index = 0; ; offset += CHUNK, index++) {
            int length = Math.min(CHUNK, plain.length - offset);
            boolean last = offset + length == plain.length;
            byte[] nonce = new byte[12];
            ByteBuffer.wrap(nonce).putLong(3, index);
            nonce[11] = (byte) (last ? 1 : 0);
            Cipher cipher;
            try {
                cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding");
            } catch (java.security.GeneralSecurityException e) {
                cipher = Cipher.getInstance("ChaCha20-Poly1305");
            }
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                    new IvParameterSpec(nonce));
            out.write(cipher.doFinal(Arrays.copyOfRange(plain, offset, offset + length)));
            if (last) return out.toByteArray();
        }
    }

    private static byte[] readMixed(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK + 25];
        int[] sizes = {1, 31, 8192, CHUNK + 17, 67};
        for (int i = 0; ; i++) {
            assertEquals(0, in.read(buffer, 3, 0));
            int b = in.read();
            if (b < 0) break;
            out.write(b);
            int n = in.read(buffer, 3, sizes[i % sizes.length]);
            if (n < 0) break;
            if (n == 0) throw new AssertionError("Read made no progress");
            out.write(buffer, 3, n);
        }
        assertEquals(-1, in.read());
        assertEquals(-1, in.read(buffer, 3, 1));
        return out.toByteArray();
    }
}
