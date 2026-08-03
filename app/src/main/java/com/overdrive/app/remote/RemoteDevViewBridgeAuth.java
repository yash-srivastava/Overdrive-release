package com.overdrive.app.remote;

import android.util.Base64;

import com.overdrive.app.auth.AuthManager;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated envelope for the app-process developer-view loopback bridge. */
public final class RemoteDevViewBridgeAuth {
    public static final long MAX_CLOCK_SKEW_MS = 30_000L;

    private static final int NONCE_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RemoteDevViewBridgeAuth() {}

    public static String sign(JSONObject request) throws Exception {
        long timestampMs = System.currentTimeMillis();
        byte[] nonceBytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonceBytes);
        String nonce = base64Url(nonceBytes);
        String requestBase64 = base64Url(request.toString().getBytes(StandardCharsets.UTF_8));
        String signature = signature(timestampMs, nonce, requestBase64);
        return new JSONObject()
            .put("timestampMs", timestampMs)
            .put("nonce", nonce)
            .put("request", requestBase64)
            .put("signature", signature)
            .toString();
    }

    public static AuthenticatedRequest verify(String envelopeText) throws Exception {
        JSONObject envelope = new JSONObject(envelopeText);
        long timestampMs = envelope.optLong("timestampMs", 0L);
        String nonce = envelope.optString("nonce", "");
        String requestBase64 = envelope.optString("request", "");
        String suppliedSignature = envelope.optString("signature", "");
        long now = System.currentTimeMillis();
        if (timestampMs <= 0L || Math.abs(now - timestampMs) > MAX_CLOCK_SKEW_MS) {
            throw new SecurityException("Bridge request timestamp is outside the allowed window");
        }
        if (nonce.isEmpty() || requestBase64.isEmpty() || suppliedSignature.isEmpty()) {
            throw new SecurityException("Bridge authentication is incomplete");
        }
        String expectedSignature = signature(timestampMs, nonce, requestBase64);
        if (!MessageDigest.isEqual(
                suppliedSignature.getBytes(StandardCharsets.US_ASCII),
                expectedSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("Bridge authentication failed");
        }
        byte[] requestBytes = Base64.decode(
            requestBase64, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return new AuthenticatedRequest(
            timestampMs,
            nonce,
            new JSONObject(new String(requestBytes, StandardCharsets.UTF_8)));
    }

    private static String signature(long timestampMs, String nonce, String requestBase64)
            throws Exception {
        AuthManager.AuthState state = AuthManager.getState();
        if (state == null || state.deviceSecret == null || state.deviceSecret.isEmpty()) {
            throw new SecurityException("Device authentication is not ready");
        }
        String content = timestampMs + "\n" + nonce + "\n" + requestBase64;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
            state.deviceSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return base64Url(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(
            bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public static final class AuthenticatedRequest {
        public final long timestampMs;
        public final String nonce;
        public final JSONObject request;

        AuthenticatedRequest(long timestampMs, String nonce, JSONObject request) {
            this.timestampMs = timestampMs;
            this.nonce = nonce;
            this.request = request;
        }
    }
}
