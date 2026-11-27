package com.overdrive.app.safekeep.crypto;

import com.google.crypto.tink.subtle.Hkdf;
import com.google.crypto.tink.subtle.X25519;
import com.overdrive.app.storage.removable.SeekableFileReader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * age v1 (https://age-encryption.org/v1) with X25519 recipients. The car only ever holds
 * recipients (public keys). Encryption is deterministic given the persisted {@link Material},
 * so an interrupted upload resumes with byte-identical ciphertext at any offset.
 */
public final class AgeCodec {

    public static final int CHUNK = 64 * 1024;
    private static final int TAG = 16;
    private static final int SEALED = CHUNK + TAG;
    private static final String INTRO = "age-encryption.org/v1\n";
    private static final byte[] X25519_INFO =
            "age-encryption.org/v1/X25519".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RNG = new SecureRandom();

    private AgeCodec() {}

    public static final class KeyPair {
        public final String identity;
        public final String recipient;

        KeyPair(String identity, String recipient) {
            this.identity = identity;
            this.recipient = recipient;
        }
    }

    public static KeyPair generate() throws GeneralSecurityException {
        byte[] priv = X25519.generatePrivateKey();
        byte[] pub = X25519.publicFromPrivate(priv);
        String identity = Bech32.encode("age-secret-key-", priv).toUpperCase(Locale.ROOT);
        return new KeyPair(identity, Bech32.encode("age", pub));
    }

    public static byte[] parseRecipient(String recipient) {
        Object[] d = Bech32.decode(recipient == null ? null : recipient.trim());
        if (!"age".equals(d[0]) || ((byte[]) d[1]).length != 32) {
            throw new IllegalArgumentException("Not an age X25519 recipient (age1…)");
        }
        return (byte[]) d[1];
    }

    public static byte[] parseIdentity(String identity) {
        Object[] d = Bech32.decode(identity == null ? null : identity.trim());
        if (!"age-secret-key-".equals(d[0]) || ((byte[]) d[1]).length != 32) {
            throw new IllegalArgumentException("Not an age identity (AGE-SECRET-KEY-1…)");
        }
        return (byte[]) d[1];
    }

    public static String recipientOf(String identity) throws GeneralSecurityException {
        return Bech32.encode("age", X25519.publicFromPrivate(parseIdentity(identity)));
    }

    /** Per-file secrets: kept only until the upload completes, then wiped from the ledger. */
    public static final class Material {
        final byte[] fileKey;
        final byte[] header;
        final byte[] nonce;

        Material(byte[] fileKey, byte[] header, byte[] nonce) {
            this.fileKey = fileKey;
            this.header = header;
            this.nonce = nonce;
        }

        public JSONObject toJson() throws Exception {
            Base64.Encoder e = Base64.getEncoder();
            return new JSONObject()
                    .put("k", e.encodeToString(fileKey))
                    .put("h", e.encodeToString(header))
                    .put("n", e.encodeToString(nonce));
        }

        public static Material fromJson(JSONObject j) {
            Base64.Decoder d = Base64.getDecoder();
            return new Material(d.decode(j.optString("k")), d.decode(j.optString("h")),
                    d.decode(j.optString("n")));
        }

        public long ciphertextLength(long plainLen) {
            return header.length + nonce.length + plainLen + (long) TAG * chunkCount(plainLen);
        }
    }

    public static Material newMaterial(List<byte[]> recipients) throws GeneralSecurityException {
        if (recipients == null || recipients.isEmpty()) {
            throw new GeneralSecurityException("No recipients");
        }
        byte[] fileKey = random(16);
        StringBuilder hdr = new StringBuilder(INTRO);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        for (byte[] pub : recipients) {
            byte[] eph = X25519.generatePrivateKey();
            byte[] share = X25519.publicFromPrivate(eph);
            byte[] shared = X25519.computeSharedSecret(eph, pub);
            if (allZero(shared)) throw new GeneralSecurityException("Low-order recipient key");
            byte[] wrapKey = Hkdf.computeHkdf("HMACSHA256", shared, concat(share, pub),
                    X25519_INFO, 32);
            byte[] body = chacha(true, wrapKey, new byte[12], fileKey);
            hdr.append("-> X25519 ").append(b64.encodeToString(share)).append('\n');
            appendWrapped(hdr, b64.encodeToString(body));
        }
        hdr.append("---");
        byte[] macInput = hdr.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] mac = hmac(Hkdf.computeHkdf("HMACSHA256", fileKey, new byte[0],
                "header".getBytes(StandardCharsets.US_ASCII), 32), macInput);
        hdr.append(' ').append(b64.encodeToString(mac)).append('\n');
        return new Material(fileKey, hdr.toString().getBytes(StandardCharsets.US_ASCII), random(16));
    }

    /** Ciphertext bytes of {@code plain} starting at ciphertext offset {@code offset}. */
    public static InputStream openCiphertext(Material m, File plain, long plainLen, long offset)
            throws IOException {
        try {
            return new CipherStream(m, plain, plainLen, offset);
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static final class CipherStream extends InputStream {
        private final byte[] prefix;
        private final SeekableFileReader raf;
        private final long plainLen;
        private final long chunks;
        private final byte[] payloadKey;
        private final long total;
        private long pos;
        private long bufIndex = -1;
        // Lazily allocated, stream-local scratch: a final short chunk uses only its valid prefix.
        private byte[] plainBuffer;
        private byte[] buf;
        private int bufLength;
        private final byte[] one = new byte[1];

        CipherStream(Material m, File plain, long plainLen, long offset)
                throws IOException, GeneralSecurityException {
            this.prefix = concat(m.header, m.nonce);
            this.plainLen = plainLen;
            this.chunks = chunkCount(plainLen);
            this.payloadKey = Hkdf.computeHkdf("HMACSHA256", m.fileKey, m.nonce,
                    "payload".getBytes(StandardCharsets.US_ASCII), 32);
            this.total = m.ciphertextLength(plainLen);
            this.raf = new SeekableFileReader(plain);
            if (raf.length() != plainLen) {
                raf.close();
                throw new IOException("Source changed size since it was queued");
            }
            this.pos = Math.max(0, Math.min(offset, total));
        }

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (pos >= total) return -1;
            if (pos < prefix.length) {
                int n = (int) Math.min(len, prefix.length - pos);
                System.arraycopy(prefix, (int) pos, b, off, n);
                pos += n;
                return n;
            }
            long rel = pos - prefix.length;
            long index = rel / SEALED;
            int within = (int) (rel % SEALED);
            if (index != bufIndex) {
                // A failed read or encryption may have overwritten scratch; never publish it.
                bufIndex = -1;
                sealChunk(index);
                bufIndex = index;
            }
            int n = Math.min(len, bufLength - within);
            System.arraycopy(buf, within, b, off, n);
            pos += n;
            return n;
        }

        private void sealChunk(long index) throws IOException {
            long start = index * CHUNK;
            int n = (int) Math.min(CHUNK, plainLen - start);
            if (plainBuffer == null) {
                int capacity = (int) Math.min(CHUNK, plainLen);
                plainBuffer = new byte[capacity];
                buf = new byte[capacity + TAG];
            }
            raf.seek(start);
            raf.readFully(plainBuffer, 0, n);
            try {
                Cipher c = initChacha(true, payloadKey, streamNonce(index, index == chunks - 1));
                // Use the caller-supplied output overload; no new result array at this layer.
                int written = c.doFinal(plainBuffer, 0, n, buf, 0);
                if (written != n + TAG) throw new IOException("Unexpected age chunk length");
                bufLength = written;
            } catch (GeneralSecurityException e) {
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public void close() throws IOException {
            // Do not retain the last plaintext chunk after the stream is closed.
            if (plainBuffer != null) Arrays.fill(plainBuffer, (byte) 0);
            raf.close();
        }
    }

    /** Whole-object decrypt for restore of small files (trip rows, settings). */
    public static byte[] decrypt(byte[] data, String identity) throws GeneralSecurityException {
        byte[] priv = parseIdentity(identity);
        byte[] pub = X25519.publicFromPrivate(priv);
        String text = new String(data, 0, Math.min(data.length, 64 * 1024), StandardCharsets.ISO_8859_1);
        if (!text.startsWith(INTRO)) throw new GeneralSecurityException("Not an age v1 file");
        int macLine = text.indexOf("\n--- ");
        if (macLine < 0) throw new GeneralSecurityException("Malformed age header");
        int headerEnd = text.indexOf('\n', macLine + 1);
        if (headerEnd < 0) throw new GeneralSecurityException("Malformed age header");
        String[] lines = text.substring(INTRO.length(), macLine).split("\n", -1);
        byte[] fileKey = null;
        Base64.Decoder b64 = Base64.getDecoder();
        for (int i = 0; i < lines.length && fileKey == null; i++) {
            if (!lines[i].startsWith("-> ")) continue;
            String[] args = lines[i].substring(3).split(" ");
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            while (j < lines.length) {
                body.append(lines[j]);
                if (lines[j].length() < 64) break;
                j++;
            }
            if (args.length == 2 && "X25519".equals(args[0])) {
                byte[] share = b64.decode(args[1]);
                byte[] shared = X25519.computeSharedSecret(priv, share);
                byte[] wrapKey = Hkdf.computeHkdf("HMACSHA256", shared, concat(share, pub),
                        X25519_INFO, 32);
                try {
                    fileKey = chacha(false, wrapKey, new byte[12], b64.decode(body.toString()));
                } catch (GeneralSecurityException ignored) {
                    // Stanza for another recipient.
                }
            }
        }
        if (fileKey == null) throw new GeneralSecurityException("This key cannot decrypt the file");
        byte[] macInput = text.substring(0, macLine + 4).getBytes(StandardCharsets.ISO_8859_1);
        byte[] expected = hmac(Hkdf.computeHkdf("HMACSHA256", fileKey, new byte[0],
                "header".getBytes(StandardCharsets.US_ASCII), 32), macInput);
        byte[] got = b64.decode(text.substring(macLine + 5, headerEnd));
        if (!MessageDigest.isEqual(expected, got)) throw new GeneralSecurityException("Header MAC mismatch");
        int p = headerEnd + 1;
        if (data.length < p + 16) throw new GeneralSecurityException("Truncated age payload");
        byte[] nonce = new byte[16];
        System.arraycopy(data, p, nonce, 0, 16);
        p += 16;
        byte[] payloadKey = Hkdf.computeHkdf("HMACSHA256", fileKey, nonce,
                "payload".getBytes(StandardCharsets.US_ASCII), 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, data.length - p));
        long index = 0;
        while (p < data.length) {
            int n = Math.min(SEALED, data.length - p);
            boolean last = p + n >= data.length;
            byte[] sealed = new byte[n];
            System.arraycopy(data, p, sealed, 0, n);
            byte[] plain = chacha(false, payloadKey, streamNonce(index, last), sealed);
            out.write(plain, 0, plain.length);
            p += n;
            index++;
        }
        return out.toByteArray();
    }

    static long chunkCount(long plainLen) {
        return plainLen <= 0 ? 1 : (plainLen + CHUNK - 1) / CHUNK;
    }

    private static byte[] streamNonce(long counter, boolean last) {
        byte[] n = new byte[12];
        long c = counter;
        for (int i = 10; i >= 0; i--) {
            n[i] = (byte) (c & 0xff);
            c >>>= 8;
        }
        n[11] = (byte) (last ? 1 : 0);
        return n;
    }

    private static byte[] chacha(boolean encrypt, byte[] key, byte[] nonce, byte[] in)
            throws GeneralSecurityException {
        return initChacha(encrypt, key, nonce).doFinal(in);
    }

    private static Cipher initChacha(boolean encrypt, byte[] key, byte[] nonce)
            throws GeneralSecurityException {
        Cipher c;
        try {
            c = Cipher.getInstance("ChaCha20/Poly1305/NoPadding");
        } catch (GeneralSecurityException e) {
            c = Cipher.getInstance("ChaCha20-Poly1305");
        }
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        return c;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static void appendWrapped(StringBuilder sb, String b64) {
        int i = 0;
        while (b64.length() - i >= 64) {
            sb.append(b64, i, i + 64).append('\n');
            i += 64;
        }
        sb.append(b64.substring(i)).append('\n');
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    private static boolean allZero(byte[] b) {
        int acc = 0;
        for (byte x : b) acc |= x;
        return acc == 0;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
