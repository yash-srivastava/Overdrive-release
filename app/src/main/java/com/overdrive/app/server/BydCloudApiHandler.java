package com.overdrive.app.server;

import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.BydCloudDeterrent;
import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * HTTP API handler for BYD Cloud account setup and testing.
 * 
 * Endpoints:
 *   GET  /api/bydcloud/status  — connection state and stored config
 *   POST /api/bydcloud/setup   — save credentials (derives keys, tests login)
 *   POST /api/bydcloud/test    — test a command (flash lights)
 *   POST /api/bydcloud/clear   — clear stored credentials
 */
public class BydCloudApiHandler {

    private static final String TAG = "BydCloudApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * Handle BYD Cloud API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/bydcloud/status") && method.equals("GET")) {
            handleStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/bydcloud/setup") && method.equals("POST")) {
            handleSetup(out, body);
            return true;
        }
        if (cleanPath.equals("/api/bydcloud/test") && method.equals("POST")) {
            handleTest(out, body);
            return true;
        }
        if (cleanPath.equals("/api/bydcloud/clear") && method.equals("POST")) {
            handleClear(out);
            return true;
        }
        return false;
    }

    /**
     * GET /api/bydcloud/status — return current BYD Cloud config state.
     */
    private static void handleStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);

        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        JSONObject status = new JSONObject();
        status.put("configured", config.isConfigured());
        status.put("verified", config.isVerified());
        status.put("enabled", config.enabled);
        status.put("username", config.username);
        status.put("vin", config.vin);
        status.put("countryCode", config.countryCode);
        status.put("region", config.region);
        // Never return derived keys to the UI
        status.put("hasLoginKey", !config.loginKey.isEmpty());
        status.put("hasCommandPwd", !config.commandPwd.isEmpty());

        response.put("status", status);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/bydcloud/setup — derive keys from raw credentials, test login, save.
     * 
     * Request body:
     * {
     *   "username": "user@example.com",
     *   "password": "rawPassword",
     *   "controlPin": "123456",
     *   "countryCode": "NL",   // optional, default "NL"
     *   "language": "en"       // optional, default "en"
     * }
     */
    private static void handleSetup(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();

        try {
            JSONObject req = new JSONObject(body);
            String username = req.optString("username", "").trim();
            String password = req.optString("password", "").trim();
            String controlPin = req.optString("controlPin", "").trim();
            String countryCode = req.optString("countryCode", "NL").trim();
            String language = req.optString("language", "en").trim();
            String region = req.optString("region", "eu").trim();

            // Auto-map countryCode from region
            java.util.Map<String, String> regionToCountry = new java.util.HashMap<>();
            regionToCountry.put("eu", "NL");
            regionToCountry.put("in", "IN");
            regionToCountry.put("au", "AU");
            regionToCountry.put("sg", "SG");
            regionToCountry.put("br", "BR");
            regionToCountry.put("jp", "JP");
            regionToCountry.put("kr-ali", "KR");
            regionToCountry.put("sa", "SA");
            regionToCountry.put("tr", "TR");
            regionToCountry.put("mx", "MX");
            regionToCountry.put("id", "ID");
            regionToCountry.put("vn", "VN");
            regionToCountry.put("no", "NO");
            regionToCountry.put("uz", "UZ");
            String mapped = regionToCountry.get(region);
            if (mapped != null) countryCode = mapped;

            // Validate inputs
            if (username.isEmpty()) {
                response.put("success", false);
                response.put("error", "Email is required");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // For updates: if password/PIN are empty, reuse existing derived keys
            BydCloudConfig existing = BydCloudConfig.fromUnifiedConfig();
            String loginKey;
            String signPassword;
            String commandPwd;

            if (!password.isEmpty()) {
                loginKey = BydCryptoUtils.pwdLoginKey(password);
                signPassword = BydCryptoUtils.md5Hex(password);
            } else if (existing.isConfigured()) {
                loginKey = existing.loginKey;
                signPassword = existing.signPassword;
            } else {
                response.put("success", false);
                response.put("error", "Password is required for first setup");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            if (!controlPin.isEmpty()) {
                if (!controlPin.matches("\\d{4,6}")) {
                    response.put("success", false);
                    response.put("error", "Control PIN must be 4-6 digits");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                commandPwd = BydCryptoUtils.md5Hex(controlPin);
            } else if (existing.isConfigured()) {
                commandPwd = existing.commandPwd;
            } else {
                response.put("success", false);
                response.put("error", "Control PIN is required for first setup");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            logger.info("Testing BYD Cloud login for: " + username.charAt(0) + "***@" + username.substring(username.indexOf('@') + 1));
            logger.info("  countryCode=" + countryCode + ", language=" + language + ", region=" + region);
            logger.info("  loginKey derived: [redacted]");
            logger.info("  commandPwd derived: [redacted]");

            // Save credentials first (so BydCloudClient can read them)
            BydCloudConfig.saveCredentials(username, loginKey, signPassword,
                    commandPwd, password, "", countryCode, language, region);
            logger.info("  Credentials saved to unified config");

            // Test login
            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            BydCloudClient client = new BydCloudClient(config);

            InputStream tablesStream = getTablesStream();
            if (tablesStream == null) {
                logger.error("  FAILED: Bangcle tables not found at /data/local/tmp/ or in assets");
                response.put("success", false);
                response.put("error", "Bangcle crypto tables not found. Reinstall the app.");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            try {
                client.init(tablesStream);
                logger.info("  Bangcle tables loaded successfully");
            } finally {
                try { tablesStream.close(); } catch (Exception ignored) {}
            }

            // Login
            logger.info("  Step 1/3: Logging in to BYD cloud...");
            client.login();
            logger.info("  Step 1/3: Login succeeded");

            // Fetch VIN
            String vin;
            logger.info("  Step 2/3: Fetching vehicle list...");
            try {
                vin = client.fetchFirstVin();
                logger.info("  Step 2/3: Found VIN=***" + vin.substring(Math.max(0, vin.length() - 4)));
            } catch (Exception e) {
                logger.warn("  Step 2/3: FAILED to fetch vehicles: " + e.getMessage());
                response.put("success", false);
                response.put("error", "Login succeeded but no vehicles found: " + e.getMessage());
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // Verify control PIN
            logger.info("  Step 3/3: Verifying control PIN...");
            try {
                client.verifyControlPassword(vin);
                logger.info("  Step 3/3: Control PIN verified");
            } catch (Exception e) {
                logger.warn("  Step 3/3: FAILED to verify PIN: " + e.getMessage());
                response.put("success", false);
                response.put("error", "Login succeeded but control PIN verification failed: " + e.getMessage());
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // Save with VIN
            BydCloudConfig.saveCredentials(username, loginKey, signPassword,
                    commandPwd, password, vin, countryCode, language, region);

            // Reset deterrent so it picks up new credentials
            BydCloudDeterrent.getInstance().reset();

            logger.info("BYD Cloud setup complete: VIN=***" + vin.substring(Math.max(0, vin.length() - 4)));

            response.put("success", true);
            response.put("vin", vin);
            response.put("message", "Connected successfully");

        } catch (Exception e) {
            logger.warn("BYD Cloud setup failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/bydcloud/test — test a command (flash lights by default).
     */
    private static void handleTest(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();

        try {
            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            if (!config.isConfigured()) {
                response.put("success", false);
                response.put("error", "BYD Cloud not configured. Set up credentials first.");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            String action = "flash_lights";
            if (body != null && !body.isEmpty()) {
                JSONObject req = new JSONObject(body);
                action = req.optString("action", "flash_lights");
            }

            BydCloudClient client = new BydCloudClient(config);
            InputStream tablesStream = getTablesStream();
            if (tablesStream == null) {
                response.put("success", false);
                response.put("error", "Bangcle crypto tables not found");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            try {
                client.init(tablesStream);
            } finally {
                try { tablesStream.close(); } catch (Exception ignored) {}
            }

            client.login();
            client.verifyControlPassword(config.vin);

            boolean success;
            switch (action) {
                case "find_car":
                    success = client.findCarNoWait(config.vin);
                    break;
                case "flash_lights":
                default:
                    success = client.flashLightsNoWait(config.vin);
                    break;
            }

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", action);
            response.put("message", success ? "Command executed" : "Command dispatched");

        } catch (Exception e) {
            logger.warn("BYD Cloud test failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/bydcloud/clear — clear stored credentials.
     */
    private static void handleClear(OutputStream out) throws Exception {
        BydCloudConfig.clearCredentials();
        BydCloudDeterrent.getInstance().reset();

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", "Credentials cleared");
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Get Bangcle tables stream — same strategy as BydCloudDeterrent.
     */
    private static InputStream getTablesStream() {
        // Try /data/local/tmp/ first
        java.io.File tablesFile = new java.io.File("/data/local/tmp/bangcle_tables.bin");
        if (tablesFile.exists() && tablesFile.length() > 0) {
            try {
                return new java.io.FileInputStream(tablesFile);
            } catch (Exception ignored) {}
        }

        // Try assets via DaemonBootstrap context
        try {
            android.content.Context ctx = com.overdrive.app.daemon.DaemonBootstrap.getContext();
            if (ctx != null) {
                return ctx.getAssets().open("byd/bangcle_tables.bin");
            }
        } catch (Exception ignored) {}

        return null;
    }
}
