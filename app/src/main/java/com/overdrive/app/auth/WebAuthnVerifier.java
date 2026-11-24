package com.overdrive.app.auth;

import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * WebAuthn checks for the passkey lock: registration with attestation "none" (key taken from the
 * browser's SPKI) and assertions. ES256 and RS256 only. Plain JVM APIs, no Android classes.
 */
public final class WebAuthnVerifier {

    public static final int ES256 = -7;
    public static final int RS256 = -257;

    private static final int FLAG_UP = 0x01;
    private static final int FLAG_UV = 0x04;
    private static final int FLAG_AT = 0x40;
    private static final int AUTH_DATA_MIN = 37;
    private static final int ATTESTED_ID_OFFSET = 55;
    private static final int MAX_CREDENTIAL_ID_BYTES = 1023;
    private static final int MAX_B64_CHARS = 16_384;
    private static final BigInteger P256_ORDER = new BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);

    /** {@code reason} is a short code when {@code ok} is false. Registration also fills the id and key. */
    public static final class Result {
        public final boolean ok;
        public final String reason;
        public final long signCount;
        /** Assertion: both counters were non-zero and this one did not grow (a cloned key, or a reset). */
        public final boolean counterNotIncreased;
        public final String credentialId;
        public final String publicKey;

        private Result(boolean ok, String reason, long signCount, boolean counterNotIncreased,
                       String credentialId, String publicKey) {
            this.ok = ok;
            this.reason = reason;
            this.signCount = signCount;
            this.counterNotIncreased = counterNotIncreased;
            this.credentialId = credentialId;
            this.publicKey = publicKey;
        }

        static Result fail(String reason) {
            return new Result(false, reason, 0L, false, null, null);
        }
    }

    private WebAuthnVerifier() {}

    /**
     * {@code navigator.credentials.create()} output. All binary arguments are base64url; {@code publicKey}
     * is {@code response.getPublicKey()} and {@code alg} is {@code response.getPublicKeyAlgorithm()}.
     */
    public static Result verifyRegistration(String rpId, byte[] challenge, String credentialId,
                                            String clientDataJSON, String authenticatorData,
                                            String publicKey, int alg) {
        try {
            if (alg != ES256 && alg != RS256) return Result.fail("unsupported_algorithm");
            byte[] id = decode(credentialId);
            byte[] clientData = decode(clientDataJSON);
            byte[] authData = decode(authenticatorData);
            byte[] spki = decode(publicKey);
            if (rpId == null || rpId.isEmpty() || id == null || id.length > MAX_CREDENTIAL_ID_BYTES
                    || clientData == null || authData == null || spki == null) {
                return Result.fail("bad_input");
            }
            String failure = checkClientData(clientData, "webauthn.create", challenge, rpId);
            if (failure == null) failure = checkAuthData(authData, rpId);
            if (failure != null) return Result.fail(failure);
            if ((authData[32] & FLAG_AT) == 0 || authData.length < ATTESTED_ID_OFFSET) {
                return Result.fail("no_attested_credential");
            }
            int idLen = ((authData[53] & 0xff) << 8) | (authData[54] & 0xff);
            // At least one byte of COSE key follows the id.
            if (idLen == 0 || idLen > MAX_CREDENTIAL_ID_BYTES || ATTESTED_ID_OFFSET + idLen >= authData.length) {
                return Result.fail("bad_auth_data");
            }
            byte[] attestedId = Arrays.copyOfRange(authData, ATTESTED_ID_OFFSET, ATTESTED_ID_OFFSET + idLen);
            if (!MessageDigest.isEqual(attestedId, id)) return Result.fail("credential_mismatch");
            if (parseKey(spki, alg) == null) return Result.fail("bad_public_key");
            return new Result(true, null, signCount(authData), false, encode(id), encode(spki));
        } catch (Throwable t) {
            return Result.fail("bad_input");
        }
    }

    /**
     * {@code navigator.credentials.get()} output checked against a stored key. The counter never fails an
     * assertion; {@link Result#counterNotIncreased} reports one that didn't grow.
     */
    public static Result verifyAssertion(String rpId, byte[] challenge, String clientDataJSON,
                                         String authenticatorData, String signature,
                                         String publicKey, int alg, long storedSignCount) {
        try {
            if (alg != ES256 && alg != RS256) return Result.fail("unsupported_algorithm");
            byte[] clientData = decode(clientDataJSON);
            byte[] authData = decode(authenticatorData);
            byte[] sig = decode(signature);
            byte[] spki = decode(publicKey);
            if (rpId == null || rpId.isEmpty() || clientData == null || authData == null
                    || sig == null || spki == null) {
                return Result.fail("bad_input");
            }
            String failure = checkClientData(clientData, "webauthn.get", challenge, rpId);
            if (failure == null) failure = checkAuthData(authData, rpId);
            if (failure != null) return Result.fail(failure);
            PublicKey key = parseKey(spki, alg);
            if (key == null) return Result.fail("bad_public_key");
            if (!signatureValid(key, alg, authData, clientData, sig)) return Result.fail("bad_signature");
            long count = signCount(authData);
            boolean notIncreased = count != 0L && storedSignCount != 0L && count <= storedSignCount;
            return new Result(true, null, count, notIncreased, null, null);
        } catch (Throwable t) {
            return Result.fail("bad_input");
        }
    }

    public static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Base64url (padding optional); null when empty or malformed. */
    public static byte[] decode(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_B64_CHARS) return null;
        try {
            byte[] out = Base64.getUrlDecoder().decode(s);
            return out.length == 0 ? null : out;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String checkClientData(byte[] clientData, String type, byte[] challenge, String rpId) {
        JSONObject o;
        try {
            o = new JSONObject(new String(clientData, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return "bad_client_data";
        }
        if (!type.equals(o.opt("type"))) return "wrong_type";
        Object sent = o.opt("challenge");
        byte[] got = sent instanceof String ? decode((String) sent) : null;
        if (got == null || challenge == null || challenge.length == 0 || !MessageDigest.isEqual(got, challenge)) {
            return "wrong_challenge";
        }
        if (!("https://" + rpId).equals(o.opt("origin"))) return "wrong_origin";
        Object cross = o.opt("crossOrigin");
        if (cross != null && cross != JSONObject.NULL && !Boolean.FALSE.equals(cross)) return "cross_origin";
        return null;
    }

    private static String checkAuthData(byte[] authData, String rpId) throws Exception {
        if (authData.length < AUTH_DATA_MIN) return "bad_auth_data";
        byte[] rpIdHash = Arrays.copyOfRange(authData, 0, 32);
        if (!MessageDigest.isEqual(rpIdHash, sha256(rpId.getBytes(StandardCharsets.UTF_8)))) return "wrong_rp";
        int flags = authData[32] & 0xff;
        if ((flags & FLAG_UP) == 0) return "no_user_presence";
        if ((flags & FLAG_UV) == 0) return "no_user_verification";
        return null;
    }

    private static long signCount(byte[] authData) {
        return ((authData[33] & 0xffL) << 24) | ((authData[34] & 0xffL) << 16)
                | ((authData[35] & 0xffL) << 8) | (authData[36] & 0xffL);
    }

    /** SPKI DER to a key fit for {@code alg}: P-256 for ES256, RSA of 2048+ bits for RS256. */
    static PublicKey parseKey(byte[] spki, int alg) {
        try {
            if (alg == ES256) {
                PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
                if (!(key instanceof ECPublicKey)) return null;
                return P256_ORDER.equals(((ECPublicKey) key).getParams().getOrder()) ? key : null;
            }
            if (alg == RS256) {
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(spki));
                if (!(key instanceof RSAPublicKey)) return null;
                RSAPublicKey rsa = (RSAPublicKey) key;
                int bits = rsa.getModulus().bitLength();
                BigInteger e = rsa.getPublicExponent();
                boolean sane = bits >= 2048 && bits <= 8192 && e.testBit(0) && e.compareTo(BigInteger.valueOf(3)) >= 0;
                return sane ? key : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ES256 signatures are ASN.1 DER, which is what SHA256withECDSA verifies.
    private static boolean signatureValid(PublicKey key, int alg, byte[] authData, byte[] clientData, byte[] sig) {
        try {
            Signature s = Signature.getInstance(alg == ES256 ? "SHA256withECDSA" : "SHA256withRSA");
            s.initVerify(key);
            s.update(authData);
            s.update(sha256(clientData));
            return s.verify(sig);
        } catch (Throwable t) {
            return false;
        }
    }
}
