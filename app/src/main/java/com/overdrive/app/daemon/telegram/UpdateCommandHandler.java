package com.overdrive.app.daemon.telegram;

import com.overdrive.app.telegram.TelegramMessages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Handles /update — channel-aware, mirroring the web update flow.
 *
 * Braveheart (rolling, latest-only):
 *   /update                 → IPC CHECK_UPDATE → status + "Install Now" button
 *   "🔄 Install Now"         → callback cmd:/update install → IPC INSTALL_UPDATE
 *
 * Alpha (archive, pick-any):
 *   /update                 → IPC LIST_VERSIONS → one button per version
 *   "v25.4"                  → callback cmd:/update install alpha-v25.4
 *                             → IPC INSTALL_UPDATE {version: "alpha-v25.4"}
 *
 * Channel switch (either channel):
 *   /update channel alpha|braveheart   (also via the inline "Switch to …" button)
 *
 * The install runs in CameraDaemon's process (via SurveillanceIpcServer). That
 * process dies mid-install — the signal to webapp/Telegram that progress has
 * passed the point of no return. The new process boots with the post-update
 * hint planted, so the next "Tunnel URL" message reads "🔄 Overdrive updated
 * to X" instead of generic "URL changed".
 */
public class UpdateCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19877;
    private static final int CHECK_TIMEOUT_MS = 18_000;
    private static final int INSTALL_TIMEOUT_MS = 25_000;
    private static final int CHANNEL_TIMEOUT_MS = 8_000;

    private static final String CHANNEL_ALPHA = "alpha";
    private static final String CHANNEL_BRAVEHEART = "braveheart";

    @Override
    public boolean canHandle(String command) {
        return "/update".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        // /update channel <alpha|braveheart>
        if (args.length > 1 && "channel".equalsIgnoreCase(args[1])) {
            String target = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
            handleSwitchChannel(chatId, target, ctx);
            return;
        }
        // /update install [tag]  — tag present = alpha pick; absent = braveheart
        if (args.length > 1 && "install".equalsIgnoreCase(args[1])) {
            String tag = args.length > 2 ? args[2] : null;
            handleInstall(chatId, tag, ctx);
            return;
        }
        handleCheck(chatId, ctx);
    }

    private void handleCheck(long chatId, CommandContext ctx) {
        ctx.sendMessage(chatId, ctx.tr("update.checking"));

        JSONObject req = new JSONObject();
        try { req.put("command", "CHECK_UPDATE"); } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, CHECK_TIMEOUT_MS);
        if (resp == null) {
            ctx.sendMessage(chatId, ctx.tr("update.service_unreachable"));
            return;
        }
        if (!resp.optBoolean("success", false)) {
            ctx.sendMessage(chatId, ctx.tr("update.check_failed",
                    ctx.technicalDetail(resp.optString("error", ""))));
            return;
        }

        String channel = resp.optString("channel", CHANNEL_ALPHA);
        // Alpha → browse-and-pick: list the archive instead of a single
        // Install button.
        if (CHANNEL_ALPHA.equals(channel)) {
            handleListAlpha(chatId, ctx);
            return;
        }

        // Braveheart → single rolling head.
        boolean available = resp.optBoolean("available", false);
        String currentVersion = resp.optString("currentVersion", "?");
        String remoteVersion = resp.optString("remoteVersion", "?");

        if (resp.has("error")) {
            ctx.sendMessage(chatId, ctx.tr("update.check_failed",
                    ctx.technicalDetail(resp.optString("error"))));
            return;
        }

        if (!available) {
            String[][][] buttons = {
                    {{ctx.tr("update.switch_alpha_all_button"), "cmd:/update channel alpha"}}
            };
            ctx.sendMessageWithButtons(chatId,
                    ctx.tr("update.up_to_date", currentVersion), buttons);
            return;
        }

        String releaseNotes = resp.optString("releaseNotes", "").trim();
        String text;
        if (!releaseNotes.isEmpty() && !TelegramMessages.mapsTechnicalDetails()) {
            String trimmed = releaseNotes.length() > 600
                    ? releaseNotes.substring(0, 600) + "…"
                    : releaseNotes;
            text = ctx.tr("update.available_with_notes",
                    currentVersion, remoteVersion, stripMarkdown(trimmed));
        } else {
            text = ctx.tr("update.available", currentVersion, remoteVersion);
        }

        String[][][] buttons = {
                {{ctx.tr("update.install_now_button"), "cmd:/update install"}},
                {{ctx.tr("update.switch_alpha_button"), "cmd:/update channel alpha"}},
                {{ctx.tr("common.cancel_button"), "cmd:/help"}}
        };
        ctx.sendMessageWithButtons(chatId, text, buttons);
    }

    /**
     * Alpha archive listing — one button per version (callback installs that
     * exact tag). Telegram inline keyboards cap usefully around a dozen rows,
     * so we surface the newest versions and note the rest live on GitHub.
     */
    private void handleListAlpha(long chatId, CommandContext ctx) {
        JSONObject req = new JSONObject();
        try { req.put("command", "LIST_VERSIONS"); } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, CHECK_TIMEOUT_MS);
        if (resp == null || !resp.optBoolean("success", false)) {
            if (resp == null) {
                ctx.sendMessage(chatId, ctx.tr("update.list_service_unreachable"));
            } else {
                ctx.sendMessage(chatId, ctx.tr("update.list_failed",
                        ctx.technicalDetail(resp.optString("error", ""))));
            }
            return;
        }
        JSONArray versions = resp.optJSONArray("versions");
        String current = resp.optString("currentVersion", "?");
        if (versions == null || versions.length() == 0) {
            ctx.sendMessage(chatId, ctx.tr("update.no_versions"));
            return;
        }

        int max = Math.min(versions.length(), 10);
        java.util.List<String[][]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < max; i++) {
            JSONObject v = versions.optJSONObject(i);
            if (v == null) continue;
            String version = v.optString("version", v.optString("tag", "?"));
            String tag = v.optString("tag", "");
            String relation = v.optString("relation", "");
            String callback = "cmd:/update install " + tag;
            // Telegram caps callback_data at 64 bytes; an overlong tag would
            // make sendMessageWithButtons 400 and the WHOLE list fail to send.
            // Skip such a row rather than nuke the entire message (realistic
            // alpha-v<semver> tags are ~12 bytes, so this never trips in
            // practice — it's a guard against a pathological tag).
            if (callback.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64) continue;
            String label = ctx.tr("update.version_button", version);
            if ("current".equals(relation)) {
                label = ctx.tr("update.version_installed_button", version);
            } else if ("older".equals(relation)) {
                label = ctx.tr("update.version_older_button", version);
            }
            rows.add(new String[][] {{label, callback}});
        }
        rows.add(new String[][] {{ctx.tr("update.switch_braveheart_button"),
                "cmd:/update channel braveheart"}});

        String text;
        if (versions.length() > max) {
            text = ctx.tr("update.alpha_list_truncated", current, max, versions.length());
        } else {
            text = ctx.tr("update.alpha_list", current);
        }

        ctx.sendMessageWithButtons(chatId, text, rows.toArray(new String[0][][]));
    }

    private void handleSwitchChannel(long chatId, String target, CommandContext ctx) {
        if (!CHANNEL_ALPHA.equals(target) && !CHANNEL_BRAVEHEART.equals(target)) {
            ctx.sendMessage(chatId, ctx.tr("update.channel_usage"));
            return;
        }
        JSONObject req = new JSONObject();
        try {
            req.put("command", "SET_CHANNEL");
            req.put("channel", target);
        } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, CHANNEL_TIMEOUT_MS);
        if (resp == null || !resp.optBoolean("success", false)) {
            if (resp == null) {
                ctx.sendMessage(chatId, ctx.tr("update.channel_service_unreachable"));
            } else {
                ctx.sendMessage(chatId, ctx.tr("update.channel_switch_failed",
                        ctx.technicalDetail(resp.optString("error", ""))));
            }
            return;
        }
        if (CHANNEL_BRAVEHEART.equals(target)) {
            ctx.sendMessage(chatId, ctx.tr("update.channel_set_braveheart"));
        } else {
            ctx.sendMessage(chatId, ctx.tr("update.channel_set_alpha"));
        }
    }

    private void handleInstall(long chatId, String tag, CommandContext ctx) {
        // No local one-shot guard here. The single-install role is owned
        // entirely by the camera daemon's shared AppUpdater.tryBeginInstall()/
        // endInstall() gate (the same gate web + app use). A held gate makes
        // handleInstallUpdate reply success=false "Update already in progress",
        // which we surface below at the success=false branch — and that gate is
        // released on every failure (onError calls endInstall), so a failed
        // download/verify can't lock the user out. A process-local AtomicBoolean
        // here used to latch true forever on the success-reply-then-failed-
        // install path (this bot process isn't killed on a pre-kill download/
        // verify failure), permanently blocking future /update installs from
        // Telegram while web/app could retry immediately — a real cross-surface
        // divergence. Relying on the shared gate removes it.
        ctx.sendMessage(chatId, ctx.tr("update.installing"));

        JSONObject req = new JSONObject();
        try {
            req.put("command", "INSTALL_UPDATE");
            // Tag present = alpha pick (a specific archived version). Absent =
            // braveheart rolling head. The server re-resolves the tag itself.
            if (tag != null && !tag.isEmpty()) req.put("version", tag);
        } catch (Exception ignored) {}

        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req, INSTALL_TIMEOUT_MS);
        if (resp == null) {
            ctx.sendMessage(chatId, ctx.tr("update.install_service_unreachable"));
            return;
        }
        if (!resp.optBoolean("success", false)) {
            ctx.sendMessage(chatId, ctx.tr("update.install_rejected",
                    ctx.technicalDetail(resp.optString("error", ""))));
            return;
        }
        // Success path: daemons are now dying. No further messages until the
        // new process boots and sends the post-update tunnel URL (or, on a
        // pre-kill download/verify failure, until the camera daemon's onError
        // sends a direct "update failed" message — see handleInstallUpdate).
        ctx.log("Update install scheduled via Telegram (remote=" +
                resp.optString("remoteVersion", "?") + ")");
    }

    /**
     * Neutralize Markdown control characters in user-supplied content (GitHub
     * release notes) so the Telegram legacy-Markdown parser doesn't reject the
     * whole message.
     */
    private static String stripMarkdown(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '_' || c == '`' || c == '[' || c == ']') continue;
            out.append(c);
        }
        return out.toString();
    }
}
