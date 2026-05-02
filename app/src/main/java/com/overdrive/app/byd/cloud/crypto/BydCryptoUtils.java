package com.overdrive.app.byd.cloud.crypto;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic utilities for the BYD cloud API.
 * 
 * Port of: pyBYD/src/pybyd/_crypto/hashing.py
 * Also matches: Niek/BYD-re/client.js (md5Hex, pwdLoginKey, sha1Mixed, etc.)
 */
public final class BydCryptoUtils {

    private static final byte[] ZERO_IV = new byte[16];

    private BydCryptoUtils() {}

    // ── MD5 ─────────────────────────────────────────────────────────────

    /**
     * Compute MD5 of a UTF-8 string, returning uppercase hex.
     */
    public static String md5Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return bytesToHexUpper(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /**
     * Derive the login AES key from a plaintext password.
     * loginKey = MD5(MD5(password).toUpperCase())
     */
    public static String pwdLoginKey(String password) {
        return md5Hex(md5Hex(password));
    }

    // ── SHA1 Mixed ──────────────────────────────────────────────────────

    /**
     * Compute SHA1 with alternating-case hex and zero filtering.
     * 
     * Port of sha1Mixed() from client.js / hashing.py:
     * 1. SHA1 digest → hex with alternating upper/lower case per byte
     * 2. Filter out '0' characters at even positions
     */
    public static String sha1Mixed(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));

            // Step 1: alternating case hex
            StringBuilder mixed = new StringBuilder(digest.length * 2);
            for (int i = 0; i < digest.length; i++) {
                String hex = String.format("%02x", digest[i] & 0xFF);
                if (i % 2 == 0) {
                    mixed.append(hex.toUpperCase());
                } else {
                    mixed.append(hex.toLowerCase());
                }
            }

            // Step 2: filter out '0' at even positions
            StringBuilder filtered = new StringBuilder();
            for (int j = 0; j < mixed.length(); j++) {
                char ch = mixed.charAt(j);
                if (ch == '0' && j % 2 == 0) {
                    continue;
                }
                filtered.append(ch);
            }

            return filtered.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    // ── Checkcode ───────────────────────────────────────────────────────

    /**
     * Compute checkcode: MD5 of compact JSON with chunk reordering.
     * Result = md5[24:32] + md5[8:16] + md5[16:24] + md5[0:8]
     * 
     * CRITICAL: Must use the exact same JSON serialization as the reference.
     * Android's JSONObject.toString() does NOT guarantee key order, but
     * BYD's server computes checkcode from the JSON string it receives.
     * Since we compute checkcode AFTER building the outer payload, and the
     * server recomputes it from the same JSON, the key order must match.
     * 
     * The reference (client.js) uses JSON.stringify() which preserves
     * insertion order. Android's JSONObject also preserves insertion order
     * in practice (uses LinkedHashMap internally since API 1), but we
     * use toString() which produces compact JSON without spaces — matching
     * JSON.stringify()'s default behavior.
     */
    public static String computeCheckcode(JSONObject payload) {
        // toString() produces compact JSON: {"key":"value","key2":"value2"}
        // This matches JSON.stringify(payload) in the reference implementation
        String json = payload.toString();
        String md5 = md5HexLower(json);
        return md5.substring(24, 32) + md5.substring(8, 16) +
               md5.substring(16, 24) + md5.substring(0, 8);
    }

    // ── Sign String ─────────────────────────────────────────────────────

    /**
     * Build the sign input string: sorted key=value pairs + &password=...
     */
    public static String buildSignString(JSONObject fields, String password) {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = fields.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append('&');
            String key = keys.get(i);
            sb.append(key).append('=').append(String.valueOf(fields.opt(key)));
        }
        sb.append("&password=").append(password);
        return sb.toString();
    }

    // ── AES-128-CBC (Inner Payload) ─────────────────────────────────────

    /**
     * Encrypt plaintext with AES-128-CBC, zero IV, returning uppercase hex.
     * Used for encryData fields.
     */
    public static String aesEncryptHex(String plaintextUtf8, String keyHex) {
        try {
            byte[] key = hexToBytes(keyHex);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(ZERO_IV));
            byte[] encrypted = cipher.doFinal(plaintextUtf8.getBytes(StandardCharsets.UTF_8));
            return bytesToHexUpper(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("AES encrypt failed", e);
        }
    }

    /**
     * Decrypt hex ciphertext with AES-128-CBC, zero IV, returning UTF-8 string.
     * Used for respondData fields.
     */
    public static String aesDecryptUtf8(String cipherHex, String keyHex) {
        try {
            byte[] key = hexToBytes(keyHex);
            byte[] ciphertext = hexToBytes(cipherHex);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(ZERO_IV));
            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES decrypt failed", e);
        }
    }

    /**
     * Generate a random 16-byte hex string (uppercase).
     */
    public static String randomHex16() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytesToHexUpper(bytes);
    }

    // ── Hex Utilities ───────────────────────────────────────────────────

    public static String bytesToHexUpper(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /** MD5 hex in lowercase (used internally for checkcode). */
    private static String md5HexLower(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
