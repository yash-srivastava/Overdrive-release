package com.overdrive.app.server;

import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.BydCloudDeterrent;
import com.overdrive.app.byd.cloud.BydCloudRegionCatalog;
import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CommandResult;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

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
        if (cleanPath.equals("/api/bydcloud/settings") && method.equals("POST")) {
            handleSettings(out, body);
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

        // Cloud push status
        status.put("cloudPush", com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().getStatusJson());

        response.put("status", status);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/bydcloud/settings — update toggle settings (cloudDataMerge).
     */
    private static void handleSettings(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);

            JSONObject fullConfig = UnifiedConfigManager.loadConfig();
            JSONObject bydCloud = fullConfig.optJSONObject("bydCloud");
            if (bydCloud == null) {
                response.put("success", false);
                response.put("error", Messages.get("errors.bydcloud_not_configured"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            if (req.has("cloudDataMerge")) {
                bydCloud.put("cloudDataMerge", req.optBoolean("cloudDataMerge", false));
            }

            UnifiedConfigManager.updateSection("bydCloud", bydCloud);

            // Sync poller state based on new toggle value
            com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().syncPollerState();

            response.put("success", true);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
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
     *   "countryCode": "GB",   // optional, maps to server region
     *   "language": "en"       // optional, defaults from country
     * }
     */
    private static void handleSetup(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();

        try {
            JSONObject req = new JSONObject(body);
            String username = req.optString("username", "").trim();
            String password = req.optString("password", "").trim();
            String controlPin = req.optString("controlPin", "").trim();
            String countryCode = BydCloudRegionCatalog.normalizeCountryCode(req.optString("countryCode", ""));
            String language = req.optString("language", "").trim();
            String requestedRegion = BydCloudRegionCatalog.normalizeRegion(req.optString("region", ""));

            // Country is the source of truth. Multiple country codes can share
            // one BYD node, so derive region from the same catalog used by the UI.
            if (countryCode.isEmpty()) {
                countryCode = BydCloudRegionCatalog.defaultCountryForRegion(requestedRegion);
            }
            if (!BydCloudRegionCatalog.isSupportedCountryCode(countryCode)) {
                logger.warn("Unsupported BYD countryCode=" + countryCode + "; falling back to default");
                countryCode = BydCloudRegionCatalog.DEFAULT_COUNTRY_CODE;
            }
            String region = BydCloudRegionCatalog.regionForCountryCode(countryCode);
            if (language.isEmpty()) {
                language = BydCloudRegionCatalog.languageForCountryCode(countryCode);
            }

            // Validate inputs
            if (username.isEmpty()) {
                response.put("success", false);
                response.put("error", Messages.get("errors.bydcloud_email_required"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // For updates: if password/PIN are empty, reuse existing derived keys
            BydCloudConfig existing = BydCloudConfig.fromUnifiedConfig();
            String loginKey;
            String signPassword;
            String commandPwd;
            String rawPasswordForSave;

            if (!password.isEmpty()) {
                loginKey = BydCryptoUtils.pwdLoginKey(password);
                signPassword = BydCryptoUtils.md5Hex(password);
                rawPasswordForSave = password;
            } else if (existing.isConfigured()) {
                loginKey = existing.loginKey;
                signPassword = existing.signPassword;
                rawPasswordForSave = existing.rawPassword;
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.bydcloud_password_required_first_setup"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            if (!controlPin.isEmpty()) {
                if (!controlPin.matches("\\d{4,6}")) {
                    response.put("success", false);
                    response.put("error", Messages.get("errors.bydcloud_pin_format"));
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                commandPwd = BydCryptoUtils.md5Hex(controlPin);
            } else if (existing.isConfigured()) {
                commandPwd = existing.commandPwd;
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.bydcloud_pin_required_first_setup"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            logger.info("Testing BYD Cloud login for: " + username.charAt(0) + "***@" + username.substring(username.indexOf('@') + 1));
            logger.info("  countryCode=" + countryCode + ", language=" + language + ", region=" + region);
            logger.info("  loginKey derived: [redacted]");
            logger.info("  commandPwd derived: [redacted]");

            // A pending OPENAIR session belongs to the previous cloud identity.
            // Clear it before replacing the stored account/VIN configuration.
            VehicleCommandRouter.getInstance().clearRemoteClimateSession();

            // Save credentials first (so BydCloudClient can read them)
            BydCloudConfig.saveCredentials(username, loginKey, signPassword,
                    commandPwd, rawPasswordForSave, "", countryCode, language, region);
            logger.info("  Credentials saved to unified config");

            // Test login
            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            BydCloudClient client = new BydCloudClient(config);

            InputStream tablesStream = getTablesStream(config);
            if (tablesStream == null) {
                logger.error("  FAILED: transport tables not found at " + ScratchPaths.getDir() + "/ or in assets");
                response.put("success", false);
                response.put("error", Messages.get("errors.bydcloud_bangcle_tables_missing_reinstall"));
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

            // Fetch VIN and vehicle type
            String vin;
            String energyType = "";
            logger.info("  Step 2/3: Fetching vehicle list...");
            try {
                String[] vinAndType = client.fetchFirstVinAndEnergyType();
                vin = vinAndType[0];
                energyType = vinAndType[1];
                logger.info("  Step 2/3: Found VIN=***" + vin.substring(Math.max(0, vin.length() - 4))
                        + " energyType=" + energyType);
            } catch (Exception e) {
                logger.warn("  Step 2/3: FAILED to fetch vehicles: " + e.getMessage());
                response.put("success", false);
                response.put("error", Messages.get("errors.bydcloud_login_no_vehicles_with_detail", e.getMessage()));
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
                response.put("error", Messages.get("errors.bydcloud_login_pin_verify_failed_with_detail", e.getMessage()));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // Preserve existing cloudDataMerge toggle state
            BydCloudConfig existing2 = BydCloudConfig.fromUnifiedConfig();
            boolean mergeToggle = existing2.cloudDataMerge;

            // Save with VIN and energyType
            BydCloudConfig.saveCredentials(username, loginKey, signPassword,
                    commandPwd, rawPasswordForSave, vin, countryCode, language, region,
                    energyType, mergeToggle);

            // Reset deterrent so it picks up new credentials
            BydCloudDeterrent.getInstance().reset();

            // Reset cloud data provider so it reconnects with new credentials
            com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().reset();

            logger.info("BYD Cloud setup complete: VIN=***" + vin.substring(Math.max(0, vin.length() - 4)));

            response.put("success", true);
            response.put("vin", vin);
            if (!energyType.isEmpty()) response.put("energyType", energyType);
            response.put("message", Messages.get("messages.bydcloud_connected"));

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
                response.put("confirmed", false);
                response.put("error", Messages.get("errors.bydcloud_not_configured_setup_required"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            String action = "flash_lights";
            if (body != null && !body.isEmpty()) {
                JSONObject req = new JSONObject(body);
                action = req.optString("action", "flash_lights");
            }
            if (!"find_car".equals(action) && !"flash_lights".equals(action)) {
                response.put("success", false);
                response.put("confirmed", false);
                response.put("error", "Unsupported cloud test action");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            VehicleCommandRouter.VehicleCommand command;
            switch (action) {
                case "find_car":
                    command = new VehicleCommandRouter.FindCarCommand();
                    break;
                case "flash_lights":
                    command = new VehicleCommandRouter.FlashLightsCommand();
                    break;
                default:
                    throw new IllegalStateException("validated cloud test action was not dispatched");
            }

            // The router owns capability discovery, serialization, terminal
            // confirmation, and timeout cancellation for every cloud action.
            CommandResult result = VehicleCommandRouter.getInstance().execute(command);
            boolean confirmed = result.outcome == VehicleCommandRouter.Outcome.SUCCESS;
            response.put("success", confirmed);
            response.put("confirmed", confirmed);
            response.put("commandSuccess", confirmed);
            response.put("action", action);
            response.put("path", result.pathString());
            response.put("message", confirmed
                    ? Messages.get("messages.bydcloud_command_executed")
                    : (result.displayMessage.isEmpty()
                            ? "Command was not confirmed" : result.displayMessage));

        } catch (Exception e) {
            logger.warn("BYD Cloud test failed: " + e.getMessage());
            response.put("success", false);
            response.put("confirmed", false);
            response.put("error", e.getMessage());
        }

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/bydcloud/clear — clear stored credentials.
     */
    private static void handleClear(OutputStream out) throws Exception {
        VehicleCommandRouter.getInstance().clearRemoteClimateSession();
        BydCloudConfig.clearCredentials();
        BydCloudDeterrent.getInstance().reset();
        // Tear down a live MQTT subscriber too — clearCredentials() flips the
        // enabled flag but the running subscriber captured its credentials by
        // value and never re-reads config, so without this it keeps a
        // 5s→300s reconnect loop + 25-min session-refresh churning against
        // now-stale creds until daemon exit. reset() → stopSubscriber()
        // cancels the scheduler, disconnects Paho, and stops the REST poller.
        // Mirrors the setup path which already calls reset() before re-arming.
        com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().reset();

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.bydcloud_credentials_cleared"));
        HttpResponse.sendJson(out, response.toString());
    }

    private static InputStream getTablesStream(BydCloudConfig config) {
        return com.overdrive.app.byd.cloud.crypto.EnvelopeCodecFactory.openTablesStream(
                config.isChinaRegion(),
                com.overdrive.app.daemon.DaemonBootstrap.getContext());
    }
}
