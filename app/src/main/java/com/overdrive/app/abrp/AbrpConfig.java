package com.overdrive.app.abrp;

import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONObject;
import com.overdrive.app.util.ScratchPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Configuration manager for ABRP telemetry integration.
 *
 * Reads and writes ABRP settings from /data/local/tmp/abrp_config.properties.
 * Mirrors the pattern from TelegramBotDaemon.loadConfig().
 */
public class AbrpConfig {

    private static final String TAG = "AbrpConfig";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String CONFIG_PATH = ScratchPaths.path("abrp_config.properties");
    private static final String PROP_USER_TOKEN = "user_token";
    private static final String PROP_ENABLED = "enabled";
    private static final String PROP_CAR_MODEL = "car_model";
    private static final String PROP_UPLOAD_INTERVAL = "upload_interval_seconds";
    private static final String PROP_API_KEY = "api_key";
    private static final String PROP_CHANGE_ONLY = "change_only";
    private static final String PROP_MIN_INTERVAL = "min_interval_seconds";
    private static final String PROP_MAX_INTERVAL = "max_interval_seconds";
    private static final String PROP_GATE_ON_APP = "gate_on_app";
    private static final String PROP_APP_PACKAGE = "app_package";
    private static final String PROP_APP_ACTIVE_MODE = "app_active_mode";
    private static final String PROP_APP_GRACE = "app_grace_seconds";

    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_UPLOAD_INTERVAL_SECONDS = 5;
    private static final boolean DEFAULT_CHANGE_ONLY = true;
    private static final int DEFAULT_MIN_INTERVAL = 5;
    private static final int DEFAULT_MAX_INTERVAL = 120;
    private static final boolean DEFAULT_GATE_ON_APP = false;
    private static final String DEFAULT_APP_PACKAGE = "com.iternio.abrpapp";
    private static final String DEFAULT_APP_ACTIVE_MODE = "foreground"; // or "running"
    private static final int DEFAULT_APP_GRACE = 90;

    private String userToken;
    private boolean enabled;
    private String carModel;
    private int uploadIntervalSeconds;
    private String apiKey;

    // Change detection + report-by-exception window
    private boolean changeOnly;
    private int minIntervalSeconds;
    private int maxIntervalSeconds;

    // "Only stream while the ABRP app is in use" gate
    private boolean gateOnApp;
    private String appPackage;
    private String appActiveMode;
    private int appGraceSeconds;

    public AbrpConfig() {
        this.userToken = null;
        this.enabled = DEFAULT_ENABLED;
        this.carModel = null;
        this.uploadIntervalSeconds = DEFAULT_UPLOAD_INTERVAL_SECONDS;
        this.apiKey = null;
        this.changeOnly = DEFAULT_CHANGE_ONLY;
        this.minIntervalSeconds = DEFAULT_MIN_INTERVAL;
        this.maxIntervalSeconds = DEFAULT_MAX_INTERVAL;
        this.gateOnApp = DEFAULT_GATE_ON_APP;
        this.appPackage = DEFAULT_APP_PACKAGE;
        this.appActiveMode = DEFAULT_APP_ACTIVE_MODE;
        this.appGraceSeconds = DEFAULT_APP_GRACE;
    }

    /**
     * Load configuration from the properties file.
     *
     * @return true if the file was read successfully, false otherwise
     */
    public boolean load() {
        try {
            File configFile = new File(CONFIG_PATH);
            if (!configFile.exists()) {
                logger.info("Config file not found: " + CONFIG_PATH);
                return false;
            }

            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            }

            userToken = props.getProperty(PROP_USER_TOKEN);
            if (userToken != null && userToken.isEmpty()) {
                userToken = null;
            }

            String enabledStr = props.getProperty(PROP_ENABLED);
            enabled = enabledStr != null ? "true".equalsIgnoreCase(enabledStr) : DEFAULT_ENABLED;

            carModel = props.getProperty(PROP_CAR_MODEL);

            apiKey = props.getProperty(PROP_API_KEY);
            if (apiKey != null && apiKey.isEmpty()) {
                apiKey = null;
            }

            String intervalStr = props.getProperty(PROP_UPLOAD_INTERVAL);
            if (intervalStr != null) {
                try {
                    uploadIntervalSeconds = Integer.parseInt(intervalStr);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid upload_interval_seconds: " + intervalStr + ", using default");
                    uploadIntervalSeconds = DEFAULT_UPLOAD_INTERVAL_SECONDS;
                }
            } else {
                uploadIntervalSeconds = DEFAULT_UPLOAD_INTERVAL_SECONDS;
            }

            String changeOnlyStr = props.getProperty(PROP_CHANGE_ONLY);
            changeOnly = changeOnlyStr != null ? "true".equalsIgnoreCase(changeOnlyStr) : DEFAULT_CHANGE_ONLY;
            minIntervalSeconds = parseInt(props.getProperty(PROP_MIN_INTERVAL), DEFAULT_MIN_INTERVAL);
            maxIntervalSeconds = parseInt(props.getProperty(PROP_MAX_INTERVAL), DEFAULT_MAX_INTERVAL);
            if (minIntervalSeconds < 1) minIntervalSeconds = 1;
            if (maxIntervalSeconds < minIntervalSeconds) maxIntervalSeconds = minIntervalSeconds;

            String gateStr = props.getProperty(PROP_GATE_ON_APP);
            gateOnApp = gateStr != null ? "true".equalsIgnoreCase(gateStr) : DEFAULT_GATE_ON_APP;
            appPackage = props.getProperty(PROP_APP_PACKAGE);
            if (appPackage == null || appPackage.isEmpty()) appPackage = DEFAULT_APP_PACKAGE;
            appActiveMode = props.getProperty(PROP_APP_ACTIVE_MODE);
            if (appActiveMode == null || appActiveMode.isEmpty()) appActiveMode = DEFAULT_APP_ACTIVE_MODE;
            appGraceSeconds = parseInt(props.getProperty(PROP_APP_GRACE), DEFAULT_APP_GRACE);

            logger.info("Config loaded: token=" + (isConfigured() ? "***" + getMaskedToken() : "not set")
                    + ", enabled=" + enabled
                    + ", car_model=" + (carModel != null ? carModel : "not set")
                    + ", interval=" + uploadIntervalSeconds + "s");
            return true;
        } catch (Exception e) {
            logger.error("Config load error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Save current configuration to the properties file.
     *
     * @return true if the file was written successfully, false otherwise
     */
    public boolean save() {
        try {
            Properties props = new Properties();
            if (userToken != null) {
                props.setProperty(PROP_USER_TOKEN, userToken);
            }
            props.setProperty(PROP_ENABLED, String.valueOf(enabled));
            if (carModel != null) {
                props.setProperty(PROP_CAR_MODEL, carModel);
            }
            if (apiKey != null) {
                props.setProperty(PROP_API_KEY, apiKey);
            }
            props.setProperty(PROP_UPLOAD_INTERVAL, String.valueOf(uploadIntervalSeconds));
            props.setProperty(PROP_CHANGE_ONLY, String.valueOf(changeOnly));
            props.setProperty(PROP_MIN_INTERVAL, String.valueOf(minIntervalSeconds));
            props.setProperty(PROP_MAX_INTERVAL, String.valueOf(maxIntervalSeconds));
            props.setProperty(PROP_GATE_ON_APP, String.valueOf(gateOnApp));
            if (appPackage != null) props.setProperty(PROP_APP_PACKAGE, appPackage);
            if (appActiveMode != null) props.setProperty(PROP_APP_ACTIVE_MODE, appActiveMode);
            props.setProperty(PROP_APP_GRACE, String.valueOf(appGraceSeconds));

            File configFile = new File(CONFIG_PATH);
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                props.store(fos, "ABRP Configuration");
            }

            logger.info("Config saved to " + CONFIG_PATH);
            return true;
        } catch (Exception e) {
            logger.error("Config save error: " + e.getMessage());
            return false;
        }
    }

    // ==================== GETTERS ====================

    public String getUserToken() {
        return userToken;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCarModel() {
        return carModel;
    }

    public int getUploadIntervalSeconds() {
        return uploadIntervalSeconds;
    }

    public String getApiKey() {
        return apiKey;
    }

    public boolean isChangeOnly() { return changeOnly; }
    public int getMinIntervalSeconds() { return minIntervalSeconds; }
    public int getMaxIntervalSeconds() { return maxIntervalSeconds; }
    public boolean isGateOnApp() { return gateOnApp; }
    public String getAppPackage() { return appPackage; }
    public String getAppActiveMode() { return appActiveMode; }
    public int getAppGraceSeconds() { return appGraceSeconds; }

    public void setChangeOnly(boolean v) { this.changeOnly = v; }
    public void setMinIntervalSeconds(int v) { this.minIntervalSeconds = Math.max(1, v); }
    public void setMaxIntervalSeconds(int v) { this.maxIntervalSeconds = v; }
    public void setGateOnApp(boolean v) { this.gateOnApp = v; }
    public void setAppPackage(String v) { this.appPackage = v; }
    public void setAppActiveMode(String v) { this.appActiveMode = v; }
    public void setAppGraceSeconds(int v) { this.appGraceSeconds = Math.max(0, v); }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Returns true if a user token is configured (non-null and non-empty).
     */
    public boolean isConfigured() {
        return userToken != null && !userToken.isEmpty();
    }

    // ==================== SETTERS ====================

    public void setUserToken(String token) {
        this.userToken = token;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setCarModel(String carModel) {
        this.carModel = carModel;
    }

    public void setUploadIntervalSeconds(int seconds) {
        this.uploadIntervalSeconds = seconds;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Remove the token and disable the service.
     */
    public void deleteToken() {
        this.userToken = null;
        this.enabled = false;
    }

    // ==================== UTILITY ====================

    /**
     * Returns a masked version of the token for display.
     * - Token >= 4 chars: "••••" + last 4 characters
     * - Token < 4 chars: "••••" + full token
     * - No token: empty string
     */
    public String getMaskedToken() {
        if (userToken == null || userToken.isEmpty()) {
            return "";
        }
        if (userToken.length() >= 4) {
            return "••••" + userToken.substring(userToken.length() - 4);
        }
        return "••••" + userToken;
    }

    /**
     * Serialize configuration to a JSONObject for IPC responses.
     * The token is masked in the output.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put(PROP_USER_TOKEN, getMaskedToken());
            json.put(PROP_ENABLED, enabled);
            json.put(PROP_CAR_MODEL, carModel != null ? carModel : "");
            json.put(PROP_UPLOAD_INTERVAL, uploadIntervalSeconds);
            json.put(PROP_CHANGE_ONLY, changeOnly);
            json.put(PROP_MIN_INTERVAL, minIntervalSeconds);
            json.put(PROP_MAX_INTERVAL, maxIntervalSeconds);
            json.put(PROP_GATE_ON_APP, gateOnApp);
            json.put(PROP_APP_PACKAGE, appPackage != null ? appPackage : "");
            json.put(PROP_APP_ACTIVE_MODE, appActiveMode != null ? appActiveMode : DEFAULT_APP_ACTIVE_MODE);
            json.put(PROP_APP_GRACE, appGraceSeconds);
            json.put("configured", isConfigured());
        } catch (Exception e) {
            logger.error("toJson error: " + e.getMessage());
        }
        return json;
    }

    @Override
    public String toString() {
        return "AbrpConfig{" +
                "token=" + (isConfigured() ? getMaskedToken() : "not set") +
                ", enabled=" + enabled +
                ", carModel=" + carModel +
                ", interval=" + uploadIntervalSeconds + "s" +
                '}';
    }
}
