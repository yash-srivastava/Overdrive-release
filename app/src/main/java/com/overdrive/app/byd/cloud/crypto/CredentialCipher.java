package com.overdrive.app.byd.cloud.crypto;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protects sensitive credential fields at rest.
 */
public final class CredentialCipher {

    private static final String TAG = "CredentialCipher";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String ENC_PREFIX = "ENC:";
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String KD_SALT = "overdrive-byd-cred-v1";
    private static final String DID_PATH = ScratchPaths.path(".byd_device_id");

    private CredentialCipher() {}

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    /**
     * Encrypt a plaintext credential for storage.
     * Returns the original value if encryption fails.
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        if (isEncrypted(plaintext)) return plaintext;

        try {
            // Always encrypt under the stable (fingerprint-free) key so a
            // later OTA can't strand this ciphertext. decrypt() still reads
            // legacy fingerprint-bound values via its fallback.
            byte[] key = deriveKey(/*includeFingerprint=*/false);
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));

            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);

            return ENC_PREFIX + android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            logger.warn("Credential protection failed: " + e.getMessage());
            return plaintext;
        }
    }

    /**
     * Decrypt a stored credential.
     * Values without the encrypted marker are returned as-is (legacy support).
     * Returns empty string on decryption failure (forces re-setup).
     *
     * <p>Tries the stable (device-id-only) key first, then falls back to the
     * legacy (device-id + firmware-fingerprint) key. The GCM auth tag makes a
     * wrong-key attempt fail cleanly (AEADBadTagException), so trying both is
     * safe and unambiguous. This lets a credential written by the old
     * fingerprint-bound scheme still decrypt on the same firmware, while every
     * NEW write uses the stable key so a future OTA (which changes
     * {@code Build.FINGERPRINT}) no longer silently invalidates it.
     */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!isEncrypted(stored)) return stored;

        final byte[] combined;
        try {
            combined = android.util.Base64.decode(
                    stored.substring(ENC_PREFIX.length()), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            logger.error("Credential recovery failed (base64): " + e.getMessage());
            return "";
        }
        // A blob too short to even hold IV(12)+GCM-tag(16) is structurally
        // undecryptable, never a legitimate plaintext. Fail CLOSED (return "")
        // like the base64-failure branch above, so botTokenPresentButUndecryptable()
        // sees it as broken and the daemon emits its park+diagnostic recovery
        // instead of building a bogus ".../botENC:.../getUpdates" URL that 404s
        // forever while logging a "configured" token.
        if (combined.length < IV_LEN + 1) return "";

        byte[] iv = new byte[IV_LEN];
        System.arraycopy(combined, 0, iv, 0, IV_LEN);
        byte[] ct = new byte[combined.length - IV_LEN];
        System.arraycopy(combined, IV_LEN, ct, 0, ct.length);

        // Stable key first (the going-forward scheme), then legacy fallback.
        String plain = tryDecrypt(iv, ct, /*includeFingerprint=*/false);
        if (plain != null) return plain;
        plain = tryDecrypt(iv, ct, /*includeFingerprint=*/true);
        if (plain != null) return plain;

        logger.error("Credential recovery failed: no key (stable or legacy) "
                + "could decrypt — device id missing or firmware changed before re-encrypt");
        return "";
    }

    /** One decrypt attempt under a specific key derivation. Null on failure. */
    private static String tryDecrypt(byte[] iv, byte[] ct, boolean includeFingerprint) {
        try {
            byte[] key = deriveKey(includeFingerprint);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;  // wrong key (bad tag) or transient — caller tries next
        }
    }

    /**
     * True when {@code stored} is encrypted but only the LEGACY
     * (fingerprint-bound) key can decrypt it — i.e. it was written by the old
     * scheme and should be re-encrypted under the stable key before a future
     * OTA strands it. Returns false for plaintext, unset, or already-stable
     * values, and for values that don't decrypt at all (nothing we can do).
     */
    public static boolean needsStableReEncrypt(String stored) {
        if (!isEncrypted(stored)) return false;
        final byte[] combined;
        try {
            combined = android.util.Base64.decode(
                    stored.substring(ENC_PREFIX.length()), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return false;
        }
        if (combined.length < IV_LEN + 1) return false;
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(combined, 0, iv, 0, IV_LEN);
        byte[] ct = new byte[combined.length - IV_LEN];
        System.arraycopy(combined, IV_LEN, ct, 0, ct.length);
        // Stable can't, but legacy can → it's a legacy ciphertext.
        return tryDecrypt(iv, ct, false) == null && tryDecrypt(iv, ct, true) != null;
    }

    /**
     * Upgrade a stored credential to the stable key if (and only if) it is a
     * legacy fingerprint-bound ciphertext. Returns the re-encrypted (stable)
     * value to persist, or {@code null} when no rewrite should happen (not
     * encrypted, already stable, unrecoverable, or a transient encrypt failure
     * that would otherwise downgrade it to plaintext). Callers persist the
     * returned value under the cross-process config lock.
     *
     * <p>Centralises the OTA-stability upgrade so every credential field
     * (telegram bot token, BYD-cloud password, NavMap key) gets the same
     * treatment instead of being stranded under the old fingerprint key.
     */
    public static String upgradeToStableOrNull(String stored) {
        if (!needsStableReEncrypt(stored)) return null;
        String plain = decrypt(stored);
        if (plain == null || plain.isEmpty()) return null;  // unrecoverable
        String reEncrypted = encrypt(plain);
        // encrypt() is fail-open (returns plaintext on JCE error). Never persist
        // a non-encrypted result over a working encrypted blob.
        if (!isEncrypted(reEncrypted)) return null;
        // Round-trip verify before committing: if the DID file flapped unreadable
        // between the decrypt above and this encrypt, deriveKey throws and the
        // isEncrypted() check above already catches that (encrypt() fails open
        // to plaintext). This verifies the rarer case of a readable-but-CHANGED
        // DID producing a validly-encrypted blob under the wrong key. Confirm it
        // actually round-trips back to the same plaintext; if not, leave the
        // working legacy blob untouched for the next run.
        if (!plain.equals(decrypt(reEncrypted))) return null;
        return reEncrypted;
    }

    /**
     * Device-bound AES key. The persisted device id ({@link #DID_PATH}) is the
     * sole device binding for the going-forward (stable) key. The legacy key
     * additionally mixes {@code Build.FINGERPRINT}, which is what made an OTA
     * silently invalidate every stored credential — the fingerprint adds no
     * real secrecy (it's world-readable) so the stable key drops it.
     */
    private static byte[] deriveKey(boolean includeFingerprint) throws Exception {
        String did = readDid();
        if (did == null) {
            // No fallback to a fixed sentinel here: this source is public, so a
            // constant key material would let anyone decrypt any credential
            // blob written while the DID was unreadable. Throwing forces
            // encrypt() to fail-open to plaintext (visibly unprotected, not
            // falsely "ENC:"-tagged) and decrypt() to fail-closed to "" —
            // both already-handled paths in the callers below.
            throw new IllegalStateException("device id unavailable at " + DID_PATH);
        }
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        String material = KD_SALT + ":" + did;
        if (includeFingerprint) {
            String bp;
            try {
                bp = android.os.Build.FINGERPRINT;
            } catch (Exception e) {
                bp = "unknown";
            }
            material = material + ":" + bp;
        }
        return d.digest(material.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the cross-UID device id (app 10xxx + daemon 2000) that
     * {@link #deriveKey} binds the credential key to. Returns {@code null} if
     * the file doesn't exist or is empty/unreadable — callers must NOT
     * substitute a fixed string here (see {@link #deriveKey}). The daemon
     * ({@code CameraDaemon.generateDeviceId()}) is responsible for seeding
     * this file with a {@code SecureRandom} value on first boot; this method
     * only reads it.
     */
    private static String readDid() {
        try {
            File f = new File(DID_PATH);
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String id = r.readLine();
                r.close();
                if (id != null && !id.trim().isEmpty()) return id.trim();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
