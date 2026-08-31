package com.overdrive.app.daemon.telegram;

import org.json.JSONObject;
import com.overdrive.app.util.ScratchPaths;

import java.io.File;
import java.io.FileWriter;

/**
 * Handles /backup — the TELEGRAM surface of the settings backup feature.
 *
 * Mirrors {@link UpdateCommandHandler}: this runs in the TelegramBotDaemon
 * process (separate from the camera daemon), so it reaches the shared
 * {@link com.overdrive.app.config.ConfigBackupService} core ONLY over IPC
 * (EXPORT_CONFIG on the camera daemon's port 19877). No backup logic is
 * duplicated here — the handler builds the bundle remotely, writes it to a
 * temp file, and delivers it as a Telegram document.
 *
 *   /backup            → EXPORT_CONFIG → send the bundle as a .json document
 *   /backup trips      → as above, plus trip history (stats only, location data)
 *
 * RESTORE is intentionally NOT offered over Telegram in this version: applying
 * a bundle is destructive and the bot has no inbound-document download path
 * yet. The handler points the user at the in-car web UI / app for restore,
 * where the daemon-liveness + confirm + same-device warnings are surfaced.
 */
public class BackupCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19877;
    private static final int EXPORT_TIMEOUT_MS = 15_000;

    @Override
    public boolean canHandle(String command) {
        return "/backup".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        if (args.length > 1 && "restore".equalsIgnoreCase(args[1])) {
            ctx.sendMessage(chatId, ctx.tr("backup.restore_unavailable"));
            return;
        }

        // `/backup trips` includes trip history (stats only); `/backup` is
        // settings-only. Trips carry location data, so it's an explicit opt-in.
        boolean includeTrips = args.length > 1 && "trips".equalsIgnoreCase(args[1]);

        ctx.sendMessage(chatId, includeTrips
                ? ctx.tr("backup.building_with_trips")
                : ctx.tr("backup.building_settings"));

        JSONObject req = new JSONObject();
        try {
            req.put("command", "EXPORT_CONFIG");
            req.put("includeTrips", includeTrips);
        } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, EXPORT_TIMEOUT_MS);
        if (resp == null) {
            ctx.sendMessage(chatId, ctx.tr("backup.service_unreachable"));
            return;
        }
        if (!resp.optBoolean("success", false)) {
            ctx.sendMessage(chatId, ctx.tr("backup.failed",
                    ctx.technicalDetail(resp.optString("error", ""))));
            return;
        }
        JSONObject bundle = resp.optJSONObject("bundle");
        if (bundle == null) {
            ctx.sendMessage(chatId, ctx.tr("backup.no_data"));
            return;
        }

        // Write to a temp file the bot can upload. The bundle contains the
        // device key material + encrypted credentials, so lock the file to
        // owner-only the moment it exists (default umask would leave it
        // world-readable in the shared /data/local/tmp), and ALWAYS delete it
        // in a finally — even if sendDocument throws — so the secrets never
        // linger on disk.
        File tmp = new File(ScratchPaths.path("overdrive_backup_export.json"));
        try {
            // Lock permissions to owner-only BEFORE any secret bytes land:
            // create the file empty, chmod it, THEN write. Setting permissions
            // after writing leaves a window where the DID + credentials are
            // world-readable on the shared /data/local/tmp.
            try { tmp.delete(); } catch (Exception ignored) {}
            if (!tmp.createNewFile()) {
                // Pre-existing (e.g. a prior crashed run) — still tighten it.
            }
            tmp.setReadable(false, false);
            tmp.setWritable(false, false);
            tmp.setReadable(true, true);
            tmp.setWritable(true, true);
            try (FileWriter w = new FileWriter(tmp)) {
                w.write(bundle.toString(2));
            }
        } catch (Exception e) {
            try { tmp.delete(); } catch (Exception ignored) {}
            ctx.sendMessage(chatId, ctx.tr("backup.write_failed",
                    ctx.technicalDetail(e.getMessage())));
            return;
        }

        String appVer = "";
        JSONObject manifest = bundle.optJSONObject("manifest");
        if (manifest != null) appVer = manifest.optString("appVersion", "");

        int tripCount = 0;
        org.json.JSONArray tripsArr = bundle.optJSONArray("trips");
        if (tripsArr != null) tripCount = tripsArr.length();

        String caption;
        if (includeTrips) {
            caption = appVer.isEmpty()
                    ? ctx.tr("backup.document_with_trips", tripCount)
                    : ctx.tr("backup.document_with_trips_version", tripCount, appVer);
        } else {
            caption = appVer.isEmpty()
                    ? ctx.tr("backup.document_settings")
                    : ctx.tr("backup.document_settings_version", appVer);
        }

        boolean sent;
        try {
            sent = ctx.sendDocument(chatId, tmp.getAbsolutePath(), caption);
        } finally {
            try { tmp.delete(); } catch (Exception ignored) {}
        }

        if (!sent) {
            ctx.sendMessage(chatId, ctx.tr("backup.upload_failed"));
        }
    }
}
