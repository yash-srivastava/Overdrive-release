package com.overdrive.app.notifications.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 8291 (Web Push aes128gcm content encoding) payload encoder.
 *
 * <p>This is the format that Chrome (FCM), Safari (web.push.apple.com), and
 * Firefox all support. The older aesgcm encoding is deprecated and not
 * implemented here.
 *
 * <p>Layout:
 * <pre>
 *   header := salt(16) || rs(4 BE) || idlen(1) || keyid(idlen)
 *   body   := AES-128-GCM(plaintext || 0x02, key, nonce)
 *   wire   := header || body
 * </pre>
 *
 * <p>Each call generates a fresh ephemeral ECDH keypair and salt, so the
 * same plaintext produces a different wire payload every time — by design.
 *
 * <p>BouncyCastle is not bundled (verified) so this uses only stock
 * {@code java.security} / {@code javax.crypto} primitives. HKDF is
 * implemented manually since Android's stock JCE doesn't expose it.
 */
public final class PushPayloadEncoder {

    private static final int RS = 4096;
    private static final SecureRandom RNG = new SecureRandom();

    public static final class Encoded {
        public final byte[] body;
        Encoded(byte[] body) { this.body = body; }
    }

    /**
     * Encode an opaque plaintext (UTF-8 JSON, etc.) for one subscription.
     *
     * @param plaintext payload bytes; must fit within
     *                  {@code RS - 17} after adding GCM tag and delimiter
     */
    public static Encoded encrypt(byte[] plaintext, byte[] subP256dh, byte[] subAuth) throws Exception {
        if (plaintext.length + 17 > RS) {
            throw new IllegalArgumentException("plaintext too large for single-record aes128gcm");
        }

        // Server ephemeral ECDH keypair — fresh per push
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair serverEph = kpg.generateKeyPair();
        byte[] serverPubRaw = uncompressedPoint((ECPublicKey) serverEph.getPublic());

        ECPublicKey subPub = decodeP256(subP256dh);

        // ECDH shared secret
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init((ECPrivateKey) serverEph.getPrivate());
        ka.doPhase(subPub, true);
        byte[] sharedSecret = ka.generateSecret();

        // Step 1: HKDF(auth, sharedSecret, info=WebPush: info\0 || ua_pub || as_pub, 32)
        byte[] keyInfo = concat(
                "WebPush: info\0".getBytes("UTF-8"),
                subP256dh,
                serverPubRaw);
        byte[] ikm = hkdf(subAuth, sharedSecret, keyInfo, 32);

        // Step 2: random salt(16), then derive CEK + nonce
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);

        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes("UTF-8"), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes("UTF-8"), 12);

        // Step 3: padded plaintext: payload || 0x02 (last-record delimiter)
        byte[] padded = new byte[plaintext.length + 1];
        System.arraycopy(plaintext, 0, padded, 0, plaintext.length);
        padded[plaintext.length] = 0x02;

        // Step 4: AES-128-GCM
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = c.doFinal(padded);

        // Step 5: prepend header
        ByteBuffer buf = ByteBuffer.allocate(16 + 4 + 1 + serverPubRaw.length + ciphertext.length);
        buf.put(salt);
        buf.putInt(RS);
        buf.put((byte) serverPubRaw.length);
        buf.put(serverPubRaw);
        buf.put(ciphertext);
        return new Encoded(buf.array());
    }

    // ==================== HKDF (RFC 5869) ====================

    /**
     * HKDF-Extract-and-Expand with HMAC-SHA-256.
     * Single-block expand only (length must be ≤ 32 here).
     */
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
        if (length > 32) throw new IllegalArgumentException("multi-block HKDF not implemented");
        // Extract: PRK = HMAC(salt, ikm)
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        // Expand: T(1) = HMAC(PRK, info || 0x01)
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 0x01);
        byte[] full = mac.doFinal();
        if (full.length == length) return full;
        byte[] out = new byte[length];
        System.arraycopy(full, 0, out, 0, length);
        return out;
    }

    // ==================== EC helpers ====================

    static byte[] uncompressedPoint(ECPublicKey pub) {
        byte[] x = unsignedBytes(pub.getW().getAffineX(), 32);
        byte[] y = unsignedBytes(pub.getW().getAffineY(), 32);
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(x, 0, out, 1, 32);
        System.arraycopy(y, 0, out, 33, 32);
        return out;
    }

    static ECPublicKey decodeP256(byte[] uncompressed) throws Exception {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("expected uncompressed P-256 point (65 bytes, 0x04 prefix)");
        }
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 33, 65));

        // Get the spec by generating a throwaway P-256 keypair — Android's
        // KeyFactory doesn't expose curve parameters directly via name.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec params = ((ECPublicKey) kpg.generateKeyPair().getPublic()).getParams();

        ECPublicKeySpec spec = new ECPublicKeySpec(new ECPoint(x, y), params);
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(spec);
    }

    private static byte[] unsignedBytes(BigInteger bi, int length) {
        byte[] signed = bi.toByteArray();
        if (signed.length == length) return signed;
        byte[] out = new byte[length];
        if (signed.length < length) {
            System.arraycopy(signed, 0, out, length - signed.length, signed.length);
        } else {
            System.arraycopy(signed, signed.length - length, out, 0, length);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
