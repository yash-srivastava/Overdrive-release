package com.overdrive.app.byd.cloud.crypto;

import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bangcle envelope encoder/decoder.
 * 
 * Envelope format: "F" + Base64(ciphertext)
 * Uses white-box AES in CBC mode with zero IV and PKCS#7 padding.
 * 
 * Port of: pyBYD/src/pybyd/_crypto/bangcle.py (BangcleCodec)
 * Also matches: Niek/BYD-re/bangcle.js (encodeEnvelope/decodeEnvelope)
 */
public final class BangcleCodec {

    private static final byte[] ZERO_IV = new byte[16];

    private volatile BangcleTables tables;

    /**
     * Load tables from an InputStream (typically from AssetManager).
     */
    public synchronized void loadTables(InputStream is) throws IOException {
        if (tables == null) {
            tables = BangcleTables.loadFromStream(is);
        }
    }

    /**
     * Load tables from raw bytes.
     */
    public synchronized void loadTables(byte[] data) throws IOException {
        if (tables == null) {
            tables = BangcleTables.loadFromBytes(data);
        }
    }

    /**
     * Check if tables are loaded.
     */
    public boolean isReady() {
        return tables != null;
    }

    private BangcleTables requireTables() {
        BangcleTables t = tables;
        if (t == null) {
            throw new IllegalStateException("Bangcle tables not loaded. Call loadTables() first.");
        }
        return t;
    }

    /**
     * Encode plaintext into a Bangcle envelope: "F" + Base64(ciphertext).
     * 
     * @param plaintext UTF-8 string to encrypt
     * @return Bangcle envelope string
     */
    public String encodeEnvelope(String plaintext) {
        BangcleTables t = requireTables();
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] padded = BangcleBlockCipher.addPkcs7(plainBytes);
        byte[] ciphertext = BangcleBlockCipher.encryptCbc(t, padded, ZERO_IV);
        return "F" + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    /**
     * Decode a Bangcle envelope back to plaintext string.
     * 
     * @param envelope Bangcle envelope (starts with "F")
     * @return Decoded plaintext
     */
    public String decodeEnvelope(String envelope) {
        BangcleTables t = requireTables();
        String cleaned = normalizeInput(envelope);
        byte[] ciphertext = Base64.decode(cleaned, Base64.DEFAULT);

        if (ciphertext.length == 0) {
            throw new IllegalArgumentException("Bangcle ciphertext is empty");
        }
        if (ciphertext.length % 16 != 0) {
            throw new IllegalArgumentException(
                    "Bangcle ciphertext length " + ciphertext.length + " not multiple of 16");
        }

        byte[] plaintext = BangcleBlockCipher.decryptCbc(t, ciphertext, ZERO_IV);
        byte[] stripped = BangcleBlockCipher.stripPkcs7(plaintext);
        return new String(stripped, StandardCharsets.UTF_8);
    }

    /**
     * Normalize envelope input: strip whitespace, handle URL-safe Base64,
     * remove "F" prefix, fix padding.
     */
    private static String normalizeInput(String envelope) {
        String cleaned = envelope.replaceAll("\\s+", "").trim();
        // URL-safe Base64 normalization
        cleaned = cleaned.replace('-', '+').replace('_', '/');

        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Bangcle input is empty");
        }
        if (!cleaned.startsWith("F")) {
            throw new IllegalArgumentException("Bangcle envelope must start with 'F'");
        }

        cleaned = cleaned.substring(1); // strip F prefix

        // Fix Base64 padding
        int remainder = cleaned.length() % 4;
        if (remainder != 0) {
            StringBuilder sb = new StringBuilder(cleaned);
            for (int i = 0; i < 4 - remainder; i++) {
                sb.append('=');
            }
            cleaned = sb.toString();
        }

        return cleaned;
    }
}
