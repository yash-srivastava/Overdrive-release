package com.overdrive.app.auth;

/** Fixed dummy validators: no real tokens, signing keys, storage or network. */
public final class AuthManager {
    public static final class JwtValidation {
        public boolean valid;
        public String error = "dummy invalid token";
    }

    public static JwtValidation validateJwt(String token) {
        JwtValidation result = new JwtValidation();
        result.valid = "DUMMY_VALID_JWT".equals(token);
        return result;
    }

    public static boolean validateThumbToken(String subject, String token) {
        return "DUMMY_VALID_THUMB".equals(token) && "dummy.jpg".equals(subject);
    }
}
