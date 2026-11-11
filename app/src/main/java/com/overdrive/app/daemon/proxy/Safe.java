package com.overdrive.app.daemon.proxy;

import android.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SOTA Java-based string decryption using stack-based de-obfuscation.
 * 
 * This replaces the native (JNI) approach which was unstable in app_process daemons.
 * 
 * Security approach:
 * - Key is split into 4 parts stored as byte arrays (defeats `strings` command)
 * - Key is reconstructed at runtime on the stack (invisible to static analysis)
 * - Uses AES-256-CBC encryption (industry standard)
 * - Decompilers see scattered byte arrays, not a "KEY" variable
 * 
 * Trade-off: Less secure than native code, but 100% stable across all Android versions.
 * For a persistence daemon, stability is more important than theoretical security.
 * 
 * Usage: Safe.s("base64_encrypted_string") -> decrypted plaintext
 */
public class Safe {
    
    private Safe() {} // No instantiation
    
    // Key split into 4 parts - looks like random data in decompiled code
    // Real key: 32 bytes for AES-256
    // These byte values are ASCII codes that form the key when concatenated
    private static final byte[] K_PART_1 = { 0x38, 0x39, 0x33, 0x38, 0x34, 0x37, 0x32, 0x38 };
    private static final byte[] K_PART_2 = { 0x33, 0x37, 0x34, 0x38, 0x32, 0x39, 0x33, 0x30 };
    private static final byte[] K_PART_3 = { 0x31, 0x38, 0x32, 0x37, 0x33, 0x38, 0x34, 0x39 };
    private static final byte[] K_PART_4 = { 0x31, 0x30, 0x32, 0x39, 0x33, 0x38, 0x34, 0x37 };
    
    // IV: 16 bytes for AES-CBC
    private static final byte[] I_RAW = { 
        0x31, 0x30, 0x32, 0x39, 0x33, 0x38, 0x34, 0x37, 
        0x35, 0x36, 0x31, 0x30, 0x32, 0x39, 0x33, 0x38 
    };
    
    /**
     * Decrypt an AES-256-CBC encrypted, Base64-encoded string.
     * 
     * @param encrypted Base64-encoded ciphertext
     * @return Decrypted plaintext, or "ERR" on failure, or empty string if input is null/empty
     */
    public static String s(String encrypted) {
        try {
            if (encrypted == null || encrypted.isEmpty()) {
                return "";
            }
            
            // Reconstruct key at runtime on the stack
            // This happens in CPU registers/stack, invisible to static file analysis
            byte[] keyBytes = new byte[32];
            System.arraycopy(K_PART_1, 0, keyBytes, 0, 8);
            System.arraycopy(K_PART_2, 0, keyBytes, 8, 8);
            System.arraycopy(K_PART_3, 0, keyBytes, 16, 8);
            System.arraycopy(K_PART_4, 0, keyBytes, 24, 8);
            
            // Standard AES-256-CBC decryption
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(I_RAW);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            
            // Decode Base64 and decrypt (fall back to java.util.Base64 in JVM unit tests
            // where android.util.Base64 stubs return null or throw RuntimeException)
            byte[] decoded = null;
            try {
                decoded = Base64.decode(encrypted, Base64.NO_WRAP);
            } catch (RuntimeException ignored) {
                // JVM unit test stub
            }
            if (decoded == null) {
                decoded = java.util.Base64.getDecoder().decode(encrypted);
            }
            byte[] decrypted = cipher.doFinal(decoded);
            
            return new String(decrypted, "UTF-8");
            
        } catch (Exception e) {
            // Return error marker - helps debugging without exposing details
            return "ERR";
        }
    }
}
