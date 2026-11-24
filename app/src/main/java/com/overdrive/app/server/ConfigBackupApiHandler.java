package com.overdrive.app.server;

import com.overdrive.app.config.ConfigBackupService;
import com.overdrive.app.updater.AppUpdater;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Settings Backup / Restore API — the WEB surface of the backup feature.
 *
 * This handler runs IN the camera-daemon process (UID 2000, same as the HTTP
 * server), so it calls the shared {@link ConfigBackupService} core DIRECTLY —
 * exactly as {@link UpdateApiHandler} calls {@link AppUpdater} directly. The
 * EXPORT_CONFIG / REPLACE_CONFIG IPC commands in {@link SurveillanceIpcServer}
 * exist for the OTHER-process surfaces (app UID, Telegram daemon); all three
 * funnel through the one core. NOTE one deliberate asymmetry: this web surface
 * additionally strips the shell gates out of an untrusted bundle (see
 * stripShellGatesIfUntrusted), which the REPLACE_CONFIG IPC path does not — that
 * path is the app's own loopback write channel.
 *
 * Endpoints (all non-public, so AuthMiddleware applies in every mode — but note
 * that is checkAuth, not checkJwtOnly, so a loopback caller still passes via the
 * Tier-2 safety net WITHOUT a token; that is exactly why the strip above exists):
 *   GET  /api/backup/export          → the bundle JSON (UI saves it as a file)
 *   POST /api/backup/import/preview  → {valid, message, warnings[]} — no write
 *   POST /api/backup/import?confirm=true → applies the bundle atomically
 *
 * Backup/restore are allowed over the tunnel (public mode) by design choice —
 * they rely on JWT auth alone, unlike OTA install which additionally blocks
 * public mode. The export bundle DOES contain device key material + encrypted
 * credentials, so the UI warns the user to keep the file private.
 *
 * SAME-DEVICE only: the UI states this, validateBundle warns on model/firmware
 * mismatch, and the bundle carries the device-id so secrets survive a reset.
 */
public class ConfigBackupApiHandler {

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.startsWith("/api/backup/export") && method.equals("GET")) {
            handleExport(path, out);
            return true;
        }
        if (path.equals("/api/backup/import/preview") && method.equals("POST")) {
            handleImport(body, out, /* preview */ true, path);
            return true;
        }
        if (path.startsWith("/api/backup/import") && method.equals("POST")) {
            handleImport(body, out, /* preview */ false, path);
            return true;
        }
        return false;
    }

    // ================== export ==================

    private static void handleExport(String path, OutputStream out) throws Exception {
        // JWT-gated (AuthMiddleware) in every mode. Allowed over the tunnel by
        // design — the bundle holds credentials, so the UI warns to keep it
        // private, but no public-mode block here (owner's choice).
        // ?trips=true opts the (large-ish, location-bearing) trip history into
        // the bundle; default is settings-only.
        boolean includeTrips = path != null && path.contains("trips=true");
        try {
            // (the login code only for a caller holding a session, not a loopback caller without one)
            JSONObject bundle = ConfigBackupService.buildBundle(
                    AppUpdater.getInstalledVersion(),
                    deviceModel(),
                    System.currentTimeMillis(),
                    includeTrips,
                    HttpServer.callerHasSession());
            HttpResponse.sendJson(out, bundle.toString());
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Could not build backup: " + e.getMessage());
        }
    }

    // ================== import / preview ==================

    private static void handleImport(String body, OutputStream out, boolean preview, String path)
            throws Exception {
        // JWT-gated (AuthMiddleware) in every mode; allowed over the tunnel by
        // design. A destructive write still needs an explicit confirm=true
        // (mirrors /api/update/install). Preview never writes, so it's exempt.
        // Parse the query param properly (not a substring match) so a stray
        // "confirm=true" inside another value can't satisfy the gate.
        if (!preview && !hasConfirmTrue(path)) {
            HttpResponse.sendJsonError(out, "Restore requires confirmation.");
            return;
        }
        if (body == null || body.trim().isEmpty()) {
            HttpResponse.sendJsonError(out, "No backup data received.");
            return;
        }

        JSONObject bundle;
        try {
            // Body is the bundle JSON directly (the UI POSTs the parsed file).
            bundle = new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "File is not a valid Overdrive backup.");
            return;
        }

        String appVer = AppUpdater.getInstalledVersion();
        String model = deviceModel();

        // Strip on BOTH paths so a preview describes what an import would actually apply.
        // Previewing the un-stripped bundle let a tunnel user be shown a restore that
        // silently differs from the one they then confirm.
        stripShellGatesIfUntrusted(bundle);
        if (!preview && !StepUpGate.requireBundle("config", "Restore a backup", out)) return;

        ConfigBackupService.ApplyResult result = preview
                ? ConfigBackupService.validateBundle(bundle, appVer, model)
                : ConfigBackupService.applyBundle(bundle, appVer, model, HttpServer.callerHasSession());

        JSONObject r = new JSONObject();
        r.put("valid", result.getSuccess());
        r.put("success", result.getSuccess());
        r.put("message", result.getMessage());
        r.put("warnings", new JSONArray(result.getWarnings()));
        r.put("restartRequired", result.getRestartRequired());
        if (!preview && result.getSuccess()) {
            // Kept for older web clients. A restored vehicle-mode change is separately marked
            // restartRequired and remains fenced to the old active mode until that restart.
            r.put("reloadRecommended", true);
        }
        HttpResponse.sendJson(out, r.toString());
    }

    /**
     * A restored bundle must not arm shell execution the caller could not have enabled
     * directly — restore is JWT-gated but deliberately allowed over a tunnel, which would
     * otherwise route straight around the {@code allowAdvanced} / {@code allowShell} checks
     * in KeymapApiHandler and AutomationApiHandler.
     *
     * <p>applyBundle deep-merges each section and only ever walks the INCOMING keys, so
     * dropping these two leaves whatever the device already has: everything else in the
     * backup still restores, and a locally signed-in restore is unaffected.
     */
    private static void stripShellGatesIfUntrusted(JSONObject bundle) {
        if (HttpServer.canGrantShellAccess()) return;
        JSONObject settings = bundle.optJSONObject("settings");
        ConfigBackupService.stripShellGates(
                settings == null ? null : settings.optJSONObject("unified"));
    }

    /** Exact-match the {@code confirm=true} query parameter (no substring match). */
    private static boolean hasConfirmTrue(String path) {
        if (path == null) return false;
        int q = path.indexOf('?');
        if (q < 0) return false;
        for (String param : path.substring(q + 1).split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "confirm".equals(kv[0]) && "true".equals(kv[1])) {
                return true;
            }
        }
        return false;
    }

    private static String deviceModel() {
        try {
            String m = android.os.Build.MODEL;
            return (m == null || m.isEmpty()) ? "unknown" : m;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
