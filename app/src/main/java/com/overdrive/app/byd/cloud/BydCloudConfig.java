package com.overdrive.app.byd.cloud;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

/**
 * BYD Cloud API configuration.
 * Reads derived credentials from the bydCloud section of UnifiedConfigManager.
 * 
 * Only stores derived keys (MD5 hashes), never raw passwords.
 */
public final class BydCloudConfig {

    private static final String BASE_URL_PREFIX = "https://dilinkappoversea-";
    private static final String BASE_URL_SUFFIX = ".byd.auto";
    private static final String DEFAULT_REGION = "eu";  // Europe (default for most overseas)
    private static final String USER_AGENT = "okhttp/4.12.0";

    public final boolean enabled;
    public final String username;
    public final String loginKey;      // MD5(MD5(password))
    public final String signPassword;  // MD5(password)
    public final String commandPwd;    // MD5(pin).toUpperCase()
    public final String rawPassword;   // Raw password — needed for login signKey field
    public final String vin;
    public final String countryCode;
    public final String language;
    public final String region;        // Server region: eu, in, sg, au, br, etc.
    public final String imeiMd5;
    public final String appInnerVersion;
    public final String appVersion;

    private BydCloudConfig(boolean enabled, String username, String loginKey,
                           String signPassword, String commandPwd, String rawPassword,
                           String vin, String countryCode, String language, String region) {
        this.enabled = enabled;
        this.username = username;
        this.loginKey = loginKey;
        this.signPassword = signPassword;
        this.commandPwd = commandPwd;
        this.rawPassword = rawPassword;
        this.vin = vin;
        this.countryCode = countryCode;
        this.language = language;
        this.region = (region != null && !region.isEmpty()) ? region : DEFAULT_REGION;
        // Device fingerprint derived from username (matches Niek/BYD-re)
        this.imeiMd5 = (username != null && !username.isEmpty())
                ? com.overdrive.app.byd.cloud.crypto.BydCryptoUtils.md5Hex(username)
                : "00000000000000000000000000000000";
        this.appInnerVersion = "323";
        this.appVersion = "3.2.3";
    }

    /**
     * Load config from UnifiedConfigManager.
     */
    public static BydCloudConfig fromUnifiedConfig() {
        JSONObject config = UnifiedConfigManager.loadConfig();
        JSONObject bydCloud = config.optJSONObject("bydCloud");
        if (bydCloud == null) {
            return new BydCloudConfig(false, "", "", "", "", "", "", "NL", "en", DEFAULT_REGION);
        }
        return new BydCloudConfig(
                bydCloud.optBoolean("enabled", false),
                bydCloud.optString("username", ""),
                bydCloud.optString("loginKey", ""),
                bydCloud.optString("signPassword", ""),
                bydCloud.optString("commandPwd", ""),
                bydCloud.optString("rawPassword", ""),
                bydCloud.optString("vin", ""),
                bydCloud.optString("countryCode", "NL"),
                bydCloud.optString("language", "en"),
                bydCloud.optString("region", DEFAULT_REGION)
        );
    }

    /**
     * Check if all required credentials are configured.
     */
    public boolean isConfigured() {
        return enabled
                && !username.isEmpty()
                && !loginKey.isEmpty()
                && !signPassword.isEmpty()
                && !commandPwd.isEmpty();
    }

    /**
     * Check if credentials have been verified (login + VIN + PIN all succeeded).
     */
    public boolean isVerified() {
        return isConfigured() && !vin.isEmpty();
    }

    public String getBaseUrl() {
        return BASE_URL_PREFIX + region + BASE_URL_SUFFIX;
    }

    public String getUserAgent() {
        return USER_AGENT;
    }

    /**
     * Save credentials to UnifiedConfigManager.
     * Called from the settings UI after deriving keys from raw password/PIN.
     */
    public static void saveCredentials(String username, String loginKey,
                                       String signPassword, String commandPwd,
                                       String rawPassword,
                                       String vin, String countryCode, String language,
                                       String region) {
        JSONObject bydCloud = new JSONObject();
        try {
            bydCloud.put("enabled", true);
            bydCloud.put("username", username);
            bydCloud.put("loginKey", loginKey);
            bydCloud.put("signPassword", signPassword);
            bydCloud.put("commandPwd", commandPwd);
            bydCloud.put("rawPassword", rawPassword);
            bydCloud.put("vin", vin);
            bydCloud.put("countryCode", countryCode);
            bydCloud.put("language", language);
            bydCloud.put("region", region);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build config JSON", e);
        }
        UnifiedConfigManager.updateSection("bydCloud", bydCloud);
    }

    /**
     * Clear stored credentials.
     */
    public static void clearCredentials() {
        JSONObject bydCloud = new JSONObject();
        try {
            bydCloud.put("enabled", false);
            bydCloud.put("username", "");
            bydCloud.put("loginKey", "");
            bydCloud.put("signPassword", "");
            bydCloud.put("commandPwd", "");
            bydCloud.put("vin", "");
        } catch (Exception ignored) {}
        UnifiedConfigManager.updateSection("bydCloud", bydCloud);
    }
}
