package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;

/**
 * Holds BYD cloud API session state after login.
 * 
 * Tokens are used to derive AES keys for post-login API calls:
 * - contentKey = MD5(encryToken) → encrypts/decrypts encryData/respondData
 * - signKey = MD5(signToken) → used in sign computation
 */
public final class BydCloudSession {

    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000; // 30 minutes

    public final String userId;
    public final String signToken;
    public final String encryToken;
    private final long loginTimeMs;
    private final long ttlMs;

    public BydCloudSession(String userId, String signToken, String encryToken) {
        this(userId, signToken, encryToken, DEFAULT_TTL_MS);
    }

    public BydCloudSession(String userId, String signToken, String encryToken, long ttlMs) {
        this.userId = userId;
        this.signToken = signToken;
        this.encryToken = encryToken;
        this.loginTimeMs = System.currentTimeMillis();
        this.ttlMs = ttlMs;
    }

    /** AES key for encryData/respondData: MD5(encryToken). */
    public String contentKey() {
        return BydCryptoUtils.md5Hex(encryToken);
    }

    /** Key for sign computation: MD5(signToken). */
    public String signKey() {
        return BydCryptoUtils.md5Hex(signToken);
    }

    /** Check if session has expired. */
    public boolean isExpired() {
        return (System.currentTimeMillis() - loginTimeMs) > ttlMs;
    }

    /** Check if session is valid (has tokens and not expired). */
    public boolean isValid() {
        return !userId.isEmpty()
                && !signToken.isEmpty()
                && !encryToken.isEmpty()
                && !isExpired();
    }
}
