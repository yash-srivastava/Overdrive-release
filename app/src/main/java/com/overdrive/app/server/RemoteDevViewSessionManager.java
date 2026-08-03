package com.overdrive.app.server;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/** In-memory, single-client capability for Remote Overdrive Dev View. */
final class RemoteDevViewSessionManager {
    static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L;
    static final long MAX_LIFETIME_MS = 8 * 60 * 60 * 1000L;

    interface Clock { long now(); }
    interface RandomBytes { void nextBytes(byte[] target); }

    static final class Session {
        final String token;
        final long createdAtMs;
        long lastUsedAtMs;

        Session(String token, long now) {
            this.token = token;
            this.createdAtMs = now;
            this.lastUsedAtMs = now;
        }
    }

    private final Clock clock;
    private final RandomBytes random;
    private Session current;

    RemoteDevViewSessionManager() {
        SecureRandom secureRandom = new SecureRandom();
        this.clock = System::currentTimeMillis;
        this.random = secureRandom::nextBytes;
    }

    RemoteDevViewSessionManager(Clock clock, RandomBytes random) {
        this.clock = clock;
        this.random = random;
    }

    synchronized Session start() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        current = new Session(toHex(bytes), clock.now());
        return current;
    }

    synchronized Session validateAndTouch(String candidate) {
        Session session = current;
        long now = clock.now();
        if (session == null || isExpired(session, now) || !constantTimeEquals(session.token, candidate)) {
            if (session != null && isExpired(session, now)) current = null;
            return null;
        }
        session.lastUsedAtMs = now;
        return session;
    }

    synchronized boolean end(String candidate) {
        if (current == null || !constantTimeEquals(current.token, candidate)) return false;
        current = null;
        return true;
    }

    synchronized boolean hasActiveSession() {
        if (current == null) return false;
        if (isExpired(current, clock.now())) {
            current = null;
            return false;
        }
        return true;
    }

    private static boolean isExpired(Session session, long now) {
        return now - session.lastUsedAtMs > IDLE_TIMEOUT_MS ||
               now - session.createdAtMs > MAX_LIFETIME_MS;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) actual = "";
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }
}
