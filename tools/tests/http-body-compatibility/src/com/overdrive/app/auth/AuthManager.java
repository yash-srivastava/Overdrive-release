package com.overdrive.app.auth;

/** No real identity, storage or cryptography is loaded by this fixture. */
public final class AuthManager {
    public static final class JwtValidation {
        public boolean valid;
        public String error = "Fixture token rejected";
    }

    public static JwtValidation validateJwt(String token) {
        JwtValidation validation = new JwtValidation();
        validation.valid = "DUMMY_VALID_JWT".equals(token);
        return validation;
    }

    public static boolean validateThumbToken(String filename, String token) {
        return false;
    }
}
